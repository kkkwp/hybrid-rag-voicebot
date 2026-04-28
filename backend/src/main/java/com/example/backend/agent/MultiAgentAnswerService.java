package com.example.backend.agent;

import com.example.backend.answer.ConsultationAnswerService;
import com.example.backend.progress.ProgressEventService;
import com.example.backend.prompt.ConsultationPromptTemplate;
import com.example.backend.search.CustomerNameCache;
import com.example.backend.search.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 멀티 에이전트 방식으로 고객 상담 질문에 답변하는 서비스.
 *
 * 동작 흐름:
 *   1. 요청에 명시된 에이전트들(delivery, refundExchange)을 가상 스레드로 병렬 실행한다.
 *   2. 각 에이전트는 담당 도메인의 ES 인덱스에서 하이브리드 검색을 수행하고 결과를 요약한다.
 *      (LLM 없이 규칙 기반으로 요약 — 비용과 지연 최소화)
 *   3. 모든 에이전트 결과를 모아 LLM(aggregator)이 최종 답변 한 문단을 생성한다.
 *
 * 왜 멀티 에이전트인가:
 *   배송 도메인과 환불/교환 도메인은 색인, 검색 전략, 정책이 다르다.
 *   단일 검색보다 도메인별로 분리해 각자 최적화된 검색을 수행한 뒤 결과를 합치면
 *   더 정확한 컨텍스트를 LLM에 제공할 수 있다.
 */
@Service
public class MultiAgentAnswerService {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentAnswerService.class);
    private static final List<String> DEFAULT_AGENTS = List.of("delivery", "refundExchange");
    private static final Set<String> SUPPORTED_AGENTS = Set.of("delivery", "refundExchange");
    private static final Pattern DELIVERY_ORDER_ID_PATTERN = Pattern.compile("\\bDLV-\\d{4}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND_ORDER_ID_PATTERN = Pattern.compile("\\bRFD-\\d{4}\\b", Pattern.CASE_INSENSITIVE);

    private final ConsultationAnswerService consultationAnswerService;
    private final ConsultationPromptTemplate promptTemplate;
    private final HybridSearchService hybridSearchService;
    private final ProgressEventService progressEventService;
    private final CustomerNameCache customerNameCache;

    public MultiAgentAnswerService(
            ConsultationAnswerService consultationAnswerService,
            ConsultationPromptTemplate promptTemplate,
            HybridSearchService hybridSearchService,
            ProgressEventService progressEventService,
            CustomerNameCache customerNameCache
    ) {
        this.consultationAnswerService = consultationAnswerService;
        this.promptTemplate = promptTemplate;
        this.hybridSearchService = hybridSearchService;
        this.progressEventService = progressEventService;
        this.customerNameCache = customerNameCache;
    }

    public MultiAgentAnswerResponse answer(MultiAgentAnswerRequest request) {
        String question = normalizeQuestion(request.question());
        int size = request.sizeOrDefault();
        List<String> agents = normalizeAgents(request.agents());
        String sessionId = request.sessionId();
        long startedAt = System.nanoTime();
        log.info("Multi-agent answer started. agents={}, size={}, question={}", agents, size, question);

        if (!hasMeaningfulQuestion(question)) {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            List<AgentResult> emptyResults = agents.stream()
                    .map(this::emptyQuestionAgentResult)
                    .toList();
            String finalAnswer = "입력하신 내용으로는 조회할 수 있는 질문이 확인되지 않았습니다. 주문번호와 함께 문의 내용을 다시 입력해 주세요. 확인 가능한 정보가 들어오면 바로 상태를 안내해 드리겠습니다.";
            log.info("Multi-agent answer skipped due to non-informative question. agents={}, elapsedMillis={}", agents, elapsedMillis);
            return new MultiAgentAnswerResponse(
                    question,
                    agents,
                    finalAnswer,
                    emptyResults,
                    new MultiAgentMetadata(
                            "llama3.1:8b",
                            "app_parallel_virtual_threads",
                            elapsedMillis,
                            false));
        }

        List<AgentResult> agentResults;
        // Java 21 가상 스레드(Virtual Thread)로 에이전트들을 병렬 실행한다.
        // 가상 스레드는 OS 스레드보다 훨씬 가볍기 때문에 ES 검색처럼 I/O 대기가 긴 작업에 효율적이다.
        // try-with-resources로 블록 종료 시 executor가 자동으로 shutdown된다.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<AgentResult>> futures = agents.stream()
                    .map(agent -> CompletableFuture.supplyAsync(() -> runAgent(agent, question, size, sessionId), executor)
                            .exceptionally(error -> AgentResult.failed(agent, rootMessage(error))))  // 한 에이전트가 실패해도 나머지는 계속 실행
                    .toList();

            agentResults = futures.stream()
                    .map(CompletableFuture::join)  // 모든 에이전트가 완료될 때까지 대기
                    .toList();
        }

        // 모든 에이전트가 근거 없음(evidenceCount=0)으로 끝난 경우 도메인 외 질문으로 간주한다.
        boolean allAgentsNoEvidence = agentResults.stream().allMatch(r -> r.evidenceCount() == 0 && !r.failed());
        if (allAgentsNoEvidence && !isDomainRelated(question)) {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return new MultiAgentAnswerResponse(
                    question, agents,
                    "요청하신 내용에 대해서 답변을 드릴 수 없어 죄송합니다. 저는 주문, 배송, 교환, 환불에 관한 내용만 지원하고 있습니다.",
                    agentResults,
                    new MultiAgentMetadata("llama3.2:3b", "app_parallel_virtual_threads", elapsedMillis, true));
        }

        // 각 에이전트 결과를 하나의 컨텍스트 문자열로 합친 뒤 LLM에게 최종 답변 생성을 요청한다.
        progressEventService.emit(sessionId, "aggregator_start", "최종 답변을 생성 중입니다.");
        String finalAnswer = consultationAnswerService.callAggregatorModel(
                promptTemplate.aggregatorMessages(question, buildAgentResultContext(agentResults)));
        progressEventService.emit(sessionId, "aggregator_done", "답변 생성 완료");
        finalAnswer = enforceEmpatheticLead(question, finalAnswer);
        finalAnswer = stripEnglishAssistantTrail(finalAnswer);
        finalAnswer = injectCustomerName(finalAnswer, agentResults);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.info("Multi-agent answer finished. agents={}, elapsedMillis={}, aggregatorSkipped={}",
                agents,
                elapsedMillis,
                false);

        return new MultiAgentAnswerResponse(
                question,
                agents,
                finalAnswer,
                agentResults,
                new MultiAgentMetadata(
                        "llama3.1:8b",
                        "app_parallel_virtual_threads",
                        elapsedMillis,
                        false));
    }

    /**
     * Aggregator가 질문 문장을 그대로 첫 문장으로 반복하는 경우
     * 공감 문장으로 시작하도록 후처리해 응답 톤을 강제한다.
     */
    private String enforceEmpatheticLead(String question, String answer) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        if (!startsWithQuestionLikeLead(question, answer)) {
            return answer;
        }

        String empathyLead = "문의 주신 상황으로 많이 답답하셨을 것 같습니다.";
        String rest = removeFirstSentence(answer);
        if (rest.isBlank()) {
            return empathyLead;
        }
        return empathyLead + " " + rest.trim();
    }

    private String injectCustomerName(String answer, List<AgentResult> agentResults) {
        if (answer == null || answer.isBlank()) return answer;
        String customerName = agentResults.stream()
                .filter(r -> !r.failed() && !r.evidence().isEmpty())
                .flatMap(r -> r.evidence().stream())
                .filter(h -> !h.indexName().equals("support-manuals-v1"))
                .map(h -> String.valueOf(h.source().getOrDefault("customerName", "")))
                .filter(name -> !name.isBlank() && !"null".equals(name))
                .findFirst()
                .orElse(null);
        if (customerName == null) return answer;
        // LLM이 이미 붙였을 수 있는 이름 제거 후 일괄 재치환 (중복 방지)
        String normalized = answer.replace(customerName + " 고객님", "고객님")
                                  .replace(customerName + "고객님", "고객님");
        return normalized.replace("고객님", customerName + " 고객님");
    }

    private static final Pattern DOMAIN_KEYWORD_PATTERN = Pattern.compile(
            "주문|배송|교환|환불|반품|취소|배달|도착|택배|운송|출발|출고|입고|처리|접수|신청|DLV|RFD",
            Pattern.CASE_INSENSITIVE
    );

    private boolean isDomainRelated(String question) {
        if (question == null) return false;
        if (DOMAIN_KEYWORD_PATTERN.matcher(question).find()) return true;
        return customerNameCache.findInQuery(question) != null;
    }

    // DLV-XXXX / RFD-XXXX 형태 주문번호를 제외한 2글자 이상 영어 단어 제거
    private static final Pattern STRAY_ENGLISH = Pattern.compile(
            "\\b(?!(?:DLV|RFD)-\\d{4}\\b)[a-zA-Z]{2,}\\b"
    );
    // 마지막 한국어 문장 종결 이후를 자르기 위한 패턴
    private static final Pattern LAST_KOREAN_SENTENCE = Pattern.compile(".*[가-힣][^가-힣]*[.!?]", Pattern.DOTALL);

    private String stripEnglishAssistantTrail(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        // 영어 단어 제거
        String result = STRAY_ENGLISH.matcher(answer).replaceAll("");
        // 영어 제거 후 남은 고아 구두점·공백 정리
        result = result.replaceAll("\\s*,\\s*,", ",")
                       .replaceAll(",\\s*\\.",".")
                       .replaceAll("\\s+,", ",")
                       .replaceAll(",\\s*$", "")
                       .replaceAll("\\s{2,}", " ")
                       .trim();
        // 마지막 한국어 문장 종결 이후 잘라냄
        Matcher m = LAST_KOREAN_SENTENCE.matcher(result);
        if (m.find()) {
            result = m.group().trim();
        }
        return result;
    }

    private boolean startsWithQuestionLikeLead(String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        String normalizedQuestion = normalizeComparable(question);
        String normalizedLead = normalizeComparable(firstSentence(answer));
        if (normalizedQuestion.isBlank() || normalizedLead.isBlank()) {
            return false;
        }
        return normalizedLead.startsWith(normalizedQuestion)
                || normalizedQuestion.startsWith(normalizedLead);
    }

    private String firstSentence(String text) {
        int idx = sentenceEndIndex(text);
        return idx < 0 ? text.trim() : text.substring(0, idx + 1).trim();
    }

    private String removeFirstSentence(String text) {
        int idx = sentenceEndIndex(text);
        return idx < 0 ? "" : text.substring(idx + 1).trim();
    }

    private int sentenceEndIndex(String text) {
        int dot = text.indexOf('.');
        int exclamation = text.indexOf('!');
        int question = text.indexOf('?');
        int idx = minPositive(dot, exclamation, question);
        if (idx >= 0) {
            return idx;
        }
        return text.indexOf('\n');
    }

    private int minPositive(int... values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0 && value < min) {
                min = value;
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private String normalizeComparable(String value) {
        return value == null ? "" : value.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        return question
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean hasMeaningfulQuestion(String question) {
        return question != null && question.matches(".*[\\p{L}\\p{N}].*");
    }

    private AgentResult emptyQuestionAgentResult(String agent) {
        return new AgentResult(
                agent,
                false,
                """
                        판단: 확인 필요
                        근거: 입력된 질문에 의미 있는 단어나 주문번호가 없어 조회를 수행하지 않았습니다.
                        다음행동: 주문번호와 문의 내용을 다시 입력하도록 안내합니다.
                        """,
                0,
                List.of(),
                "app_rrf",
                60,
                0,
                null
        );
    }

    /**
     * 단일 도메인 에이전트를 실행한다.
     * ES 하이브리드 검색 → 규칙 기반 요약의 순서로 동작한다.
     * LLM을 호출하지 않으므로 빠르고 비용이 없다.
     */
    private AgentResult runAgent(String agent, String question, int size, String sessionId) {
        long startedAt = System.nanoTime();
        log.info("Domain agent started. agent={}", agent);
        progressEventService.emit(sessionId, "agent_start:" + agent, agentDisplayName(agent) + " 실행 중");
        try {
            HybridSearchService.AgentSearchResult searchResult = hybridSearchService.searchForAgent(agent, question, size);
            if (!hasReliableOrderEvidence(agent, question, searchResult.hits())) {
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                log.info("Domain agent finished with low-confidence evidence. agent={}, elapsedMillis={}", agent, elapsedMillis);
                progressEventService.emit(sessionId, "agent_done:" + agent, agentDisplayName(agent) + " 완료");
                return new AgentResult(
                        agent,
                        false,
                        """
                                판단: 확인 필요
                                근거: 주문번호가 없거나 텍스트 근거가 부족해 조회 결과를 확정할 수 없습니다.
                                다음행동: 주문번호와 문의 내용을 다시 입력해 주시면 정확히 확인해 안내하겠습니다.
                                """,
                        0,
                        List.of(),
                        searchResult.fusionStrategy(),
                        searchResult.rankConstant(),
                        elapsedMillis,
                        null
                );
            }
            // LLM 없이 검색 결과를 정해진 형식(판단/근거/다음행동)으로 요약한다.
            String answer = buildDeterministicAgentSummary(agent, searchResult.hits());
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            log.info("Domain agent finished. agent={}, elapsedMillis={}", agent, elapsedMillis);
            progressEventService.emit(sessionId, "agent_done:" + agent, agentDisplayName(agent) + " 완료");

            return new AgentResult(
                    agent,
                    false,
                    answer,
                    searchResult.hits().size(),
                    searchResult.hits(),
                    searchResult.fusionStrategy(),
                    searchResult.rankConstant(),
                    elapsedMillis,
                    null);
        } catch (Exception error) {
            log.warn("Domain agent failed. agent={}, error={}", agent, rootMessage(error));
            progressEventService.emit(sessionId, "agent_done:" + agent, agentDisplayName(agent) + " 실패");
            return AgentResult.failed(agent, rootMessage(error));
        }
    }

    private String agentDisplayName(String agent) {
        return switch (agent) {
            case "delivery" -> "Delivery Agent";
            case "refundExchange" -> "환불/교환 Agent";
            default -> agent + " Agent";
        };
    }

    private boolean hasReliableOrderEvidence(
            String agent,
            String question,
            List<HybridSearchService.AgentSearchResult.AgentSearchHitItem> hits
    ) {
        // support-manuals 문서는 주문 상태를 확정하는 근거가 될 수 없으므로 주문 인덱스 hit만 본다.
        HybridSearchService.AgentSearchResult.AgentSearchHitItem orderHit = hits.stream()
                .filter(h -> !h.indexName().equals("support-manuals-v1"))
                .findFirst()
                .orElse(null);
        if (orderHit == null) {
            return false;
        }
        // lexicalRank가 있으면 텍스트 매칭이 실제로 발생했으므로 신뢰 가능한 근거로 본다.
        if (orderHit.lexicalRank() != null) {
            return true;
        }
        // score >= 1.0 이면 주문번호 또는 고객이름 term 조회로 exactMatch 보너스가 붙은 것 → 신뢰 가능
        if (orderHit.score() != null && orderHit.score() >= 1.0) {
            return true;
        }
        // vector-only hit는 의미 유사도 1등일 뿐이라 오탐이 가능하다.
        // 따라서 질문에 명시된 주문번호와 검색 hit id가 일치할 때만 확정 답변을 허용한다.
        String explicitOrderId = extractOrderId(agent, question);
        return explicitOrderId != null && explicitOrderId.equalsIgnoreCase(orderHit.id());
    }

    private String extractOrderId(String agent, String question) {
        Pattern pattern = switch (agent) {
            case "delivery" -> DELIVERY_ORDER_ID_PATTERN;
            case "refundExchange" -> REFUND_ORDER_ID_PATTERN;
            default -> null;
        };
        if (pattern == null || question == null || question.isBlank()) {
            return null;
        }
        var matcher = pattern.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().toUpperCase();
    }

    private String buildOrderEvidence(Map<String, Object> source, String defaultId) {
        String content = String.valueOf(source.getOrDefault("content", defaultId));
        String customerName = String.valueOf(source.getOrDefault("customerName", ""));
        if (!customerName.isBlank() && !"null".equals(customerName)) {
            return "고객명: " + customerName + "\n" + content;
        }
        return content;
    }

    /**
     * 검색 결과(hits)를 LLM 없이 규칙 기반으로 "판단 / 근거 / 다음행동" 형식의 텍스트로 변환한다.
     * 이 텍스트는 이후 aggregator LLM의 입력 컨텍스트로 사용된다.
     */
    private String buildDeterministicAgentSummary(
            String agent,
            List<HybridSearchService.AgentSearchResult.AgentSearchHitItem> hits
    ) {
        if (hits.isEmpty()) {
            return """
                    판단: 확인 필요
                    근거: 관련 데이터 없음
                    다음행동: 상담사가 주문 정보를 확인한 뒤 안내해야 합니다.
                    """;
        }

        HybridSearchService.AgentSearchResult.AgentSearchHitItem orderHit = hits.stream()
                .filter(h -> !h.indexName().equals("support-manuals-v1"))
                .findFirst()
                .orElse(null);
        HybridSearchService.AgentSearchResult.AgentSearchHitItem manualHit = hits.stream()
                .filter(h -> h.indexName().equals("support-manuals-v1"))
                .findFirst()
                .orElse(null);

        String orderEvidence = orderHit != null
                ? buildOrderEvidence(orderHit.source(), orderHit.id())
                : "주문 상태 데이터 없음";
        String manualEvidence = manualHit != null
                ? String.valueOf(manualHit.source().getOrDefault("content", ""))
                : "";

        String evidence = manualEvidence.isBlank()
                ? orderEvidence
                : orderEvidence + "\n매뉴얼: " + manualEvidence;

        String judgment = switch (agent) {
            case "delivery" -> "배송 상태 조회 완료";
            case "refundExchange" -> "환불/교환 처리 상태 조회 완료";
            default -> "확인 필요";
        };
        String nextAction = switch (agent) {
            case "delivery" -> "배송 상태와 예상 도착일을 고객에게 안내하고, 이동이 없으면 배송사 확인을 권고합니다.";
            case "refundExchange" -> "처리 상태와 다음 조치를 고객에게 안내합니다.";
            default -> "상담사가 추가 정보를 확인한 뒤 안내해야 합니다.";
        };

        return """
                판단: %s
                근거: %s
                다음행동: %s
                """.formatted(judgment, evidence, nextAction);
    }

    private String buildAgentResultContext(List<AgentResult> agentResults) {
        StringBuilder context = new StringBuilder();
        for (AgentResult result : agentResults) {
            context.append("- agent=").append(result.agent())
                    .append(", failed=").append(result.failed())
                    .append(", evidenceCount=").append(result.evidenceCount())
                    .append(", answer=").append(result.answer())
                    .append(", evidence=").append(firstEvidenceSummary(result));
            if (result.error() != null) {
                context.append(", error=").append(result.error());
            }
            context.append("\n");
        }
        return context.toString();
    }

    private String firstEvidenceSummary(AgentResult result) {
        if (result.evidence().isEmpty()) {
            return "none";
        }
        HybridSearchService.AgentSearchResult.AgentSearchHitItem hit = result.evidence().getFirst();
        String content = String.valueOf(hit.source().getOrDefault("content", ""));
        return "id=%s, index=%s, content=%s".formatted(hit.id(), hit.indexName(), content);
    }

    /**
     * 요청된 에이전트 목록을 검증·정규화한다.
     * 지원하지 않는 에이전트명은 무시하고, 비어있으면 기본값(delivery)으로 fallback한다.
     * LinkedHashSet을 사용해 중복 제거와 입력 순서를 동시에 보장한다.
     */
    private List<String> normalizeAgents(List<String> requestedAgents) {
        List<String> source = requestedAgents == null || requestedAgents.isEmpty() ? DEFAULT_AGENTS : requestedAgents;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String agent : source) {
            if (SUPPORTED_AGENTS.contains(agent)) {
                normalized.add(agent);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add("delivery");
        }
        return List.copyOf(normalized);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    public record MultiAgentAnswerRequest(
            String question,
            List<String> agents,
            Integer size,
            String sessionId
    ) {
        private int sizeOrDefault() {
            if (size == null) return 2;
            return Math.max(1, Math.min(size, 10));
        }
    }

    public record MultiAgentAnswerResponse(
            String question,
            List<String> agents,
            String finalAnswer,
            List<AgentResult> agentResults,
            MultiAgentMetadata metadata
    ) {
    }

    public record AgentResult(
            String agent,
            boolean failed,
            String answer,
            int evidenceCount,
            List<HybridSearchService.AgentSearchResult.AgentSearchHitItem> evidence,
            String fusionStrategy,
            int rankConstant,
            long elapsedMillis,
            String error
    ) {
        private static AgentResult failed(String agent, String error) {
            return new AgentResult(agent, true, "", 0, List.of(), null, 0, 0, error);
        }
    }

    public record MultiAgentMetadata(
            String mainModel,
            String executionStrategy,
            long elapsedMillis,
            boolean aggregatorSkipped
    ) {
    }
}
