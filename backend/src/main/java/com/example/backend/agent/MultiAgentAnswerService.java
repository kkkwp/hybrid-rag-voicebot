package com.example.backend.agent;

import com.example.backend.agent.dto.AgentResult;
import com.example.backend.agent.dto.MultiAgentAnswerRequest;
import com.example.backend.agent.dto.MultiAgentAnswerResponse;
import com.example.backend.agent.dto.MultiAgentMetadata;
import com.example.backend.search.HybridSearchService;
import com.example.backend.search.dto.AgentSearchResult;
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
    private static final Pattern SELF_INTRODUCED_NAME_PATTERN =
            Pattern.compile("([가-힣]{2,4})\\s*(?:입니다|이에요|예요|인데요|라고\\s*합니다)");

    private final ConsultationAnswerService consultationAnswerService;
    private final ConsultationPromptTemplate promptTemplate;
    private final HybridSearchService hybridSearchService;

    public MultiAgentAnswerService(
            ConsultationAnswerService consultationAnswerService,
            ConsultationPromptTemplate promptTemplate,
            HybridSearchService hybridSearchService
    ) {
        this.consultationAnswerService = consultationAnswerService;
        this.promptTemplate = promptTemplate;
        this.hybridSearchService = hybridSearchService;
    }

    public MultiAgentAnswerResponse answer(MultiAgentAnswerRequest request) {
        String question = normalizeQuestion(request.question());
        int size = request.sizeOrDefault();
        List<String> agents = normalizeAgents(request.agents());
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
                    .map(agent -> CompletableFuture.supplyAsync(() -> runAgent(agent, question, size), executor)
                            .exceptionally(error -> AgentResult.failed(agent, rootMessage(error))))  // 한 에이전트가 실패해도 나머지는 계속 실행
                    .toList();

            agentResults = futures.stream()
                    .map(CompletableFuture::join)  // 모든 에이전트가 완료될 때까지 대기
                    .toList();
        }

        // 각 에이전트 결과를 하나의 컨텍스트 문자열로 합친 뒤 LLM에게 최종 답변 생성을 요청한다.
        String finalAnswer = consultationAnswerService.callAggregatorModel(
                promptTemplate.aggregatorMessages(question, buildAgentResultContext(agentResults)));
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
    private AgentResult runAgent(String agent, String question, int size) {
        long startedAt = System.nanoTime();
        log.info("Domain agent started. agent={}", agent);
        try {
            AgentSearchResult searchResult = hybridSearchService.searchForAgent(agent, question, size);
            if (!hasReliableOrderEvidence(agent, question, searchResult.hits())) {
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                log.info("Domain agent finished with low-confidence evidence. agent={}, elapsedMillis={}", agent, elapsedMillis);
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
            return AgentResult.failed(agent, rootMessage(error));
        }
    }

    private boolean hasReliableOrderEvidence(
            String agent,
            String question,
            List<AgentSearchResult.AgentSearchHitItem> hits
    ) {
        List<String> customerNames = extractCustomerNames(question);
        // support-manuals 문서는 주문 상태를 확정하는 근거가 될 수 없으므로 주문 인덱스 hit만 본다.
        AgentSearchResult.AgentSearchHitItem orderHit = hits.stream()
                .filter(h -> !h.indexName().equals("support-manuals-v1"))
                .findFirst()
                .orElse(null);
        if (orderHit == null) {
            return false;
        }
        // 질문에 이름 자기소개가 있으면 hit의 customerName과 정확히 일치할 때만 신뢰한다.
        if (!customerNames.isEmpty()) {
            Object value = orderHit.source().get("customerName");
            if (value == null) {
                return false;
            }
            String hitCustomerName = value.toString().trim();
            return customerNames.stream().anyMatch(name -> name.equals(hitCustomerName));
        }
        // lexicalRank가 있으면 텍스트 매칭이 실제로 발생했으므로 신뢰 가능한 근거로 본다.
        if (orderHit.lexicalRank() != null) {
            return true;
        }
        // vector-only hit는 의미 유사도 1등일 뿐이라 오탐이 가능하다.
        // 따라서 질문에 명시된 주문번호와 검색 hit id가 일치할 때만 확정 답변을 허용한다.
        String explicitOrderId = extractOrderId(agent, question);
        return explicitOrderId != null && explicitOrderId.equalsIgnoreCase(orderHit.id());
    }

    private List<String> extractCustomerNames(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        var matcher = SELF_INTRODUCED_NAME_PATTERN.matcher(question);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
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

    /**
     * 검색 결과(hits)를 LLM 없이 규칙 기반으로 "판단 / 근거 / 다음행동" 형식의 텍스트로 변환한다.
     * 이 텍스트는 이후 aggregator LLM의 입력 컨텍스트로 사용된다.
     */
    private String buildDeterministicAgentSummary(
            String agent,
            List<AgentSearchResult.AgentSearchHitItem> hits
    ) {
        if (hits.isEmpty()) {
            return """
                    판단: 확인 필요
                    근거: 관련 데이터 없음
                    다음행동: 상담사가 주문 정보를 확인한 뒤 안내해야 합니다.
                    """;
        }

        AgentSearchResult.AgentSearchHitItem orderHit = hits.stream()
                .filter(h -> !h.indexName().equals("support-manuals-v1"))
                .findFirst()
                .orElse(null);
        AgentSearchResult.AgentSearchHitItem manualHit = hits.stream()
                .filter(h -> h.indexName().equals("support-manuals-v1"))
                .findFirst()
                .orElse(null);

        String orderEvidence = orderHit != null
                ? String.valueOf(orderHit.source().getOrDefault("content", orderHit.id()))
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
        AgentSearchResult.AgentSearchHitItem hit = result.evidence().getFirst();
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
}
