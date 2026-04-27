package com.example.backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 키워드 검색과 벡터 검색을 결합한 하이브리드 검색 서비스.
 *
 * 검색 방식 세 가지를 조합한다:
 *   1. 정확 일치(Exact match): 질문에서 주문번호(DLV-xxxx, RFD-xxxx)를 추출해 ES ids 쿼리로 직접 조회.
 *      주문번호가 있을 때 해당 문서가 반드시 결과에 포함되도록 보장한다.
 *   2. 키워드 검색(Lexical): ES multi_match 쿼리. "배송 지연" 같은 단어 일치에 강하다.
 *   3. 벡터 검색(Vector / Semantic): 질문을 임베딩(숫자 벡터)으로 변환해 의미가 비슷한 문서를 찾는다.
 *      "언제 와요?" ↔ "배송 예정일 문의"처럼 단어가 달라도 의미가 같으면 찾을 수 있다.
 *
 * 세 결과를 RRF(Reciprocal Rank Fusion) 알고리즘으로 합산해 최종 순위를 결정한다.
 * RRF는 각 검색 방식에서의 순위(rank)를 1/(k+rank) 점수로 변환하고 더하는 방식으로,
 * 특정 검색 방식의 원점수 스케일에 영향받지 않고 안정적으로 결과를 합칠 수 있다.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    // RRF 공식: score = 1 / (RANK_CONSTANT + rank). 상수가 클수록 순위 간 점수 차이가 줄어든다. 보통 60을 권장값으로 사용한다.
    private static final int RANK_CONSTANT = 60;
    private static final double LEXICAL_WEIGHT = 1.0;  // 키워드 검색 결과의 가중치
    private static final double VECTOR_WEIGHT = 1.0;   // 벡터 검색 결과의 가중치
    private static final double EXACT_MATCH_BONUS = 1.0; // 주문번호 정확 일치 시 추가 점수
    private static final Pattern DELIVERY_ORDER_ID_PATTERN = Pattern.compile("\\bDLV-\\d{4}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND_ORDER_ID_PATTERN = Pattern.compile("\\bRFD-\\d{4}\\b", Pattern.CASE_INSENSITIVE);

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_CLASS =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;

    public HybridSearchService(ElasticsearchClient esClient, EmbeddingModel embeddingModel) {
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
    }

    private Double maxNullable(Double current, Double candidate) {
        if (current == null) return candidate;
        if (candidate == null) return current;
        return Math.max(current, candidate);
    }

    private Integer minNullable(Integer current, Integer candidate) {
        if (current == null) return candidate;
        if (candidate == null) return current;
        return Math.min(current, candidate);
    }

    private List<Float> toFloatList(float[] vector) {
        return java.util.stream.IntStream.range(0, vector.length)
                .mapToObj(i -> vector[i])
                .toList();
    }

    /**
     * 특정 도메인 에이전트를 위한 하이브리드 검색을 수행한다.
     *
     * 주문 데이터 인덱스와 상담 매뉴얼 인덱스를 동시에 검색하고,
     * RRF로 합산된 최종 상위 size개의 결과를 반환한다.
     *
     * @param agent 에이전트 식별자 ("delivery" 또는 "refundExchange")
     * @param query 고객 질문 원문
     * @param size  반환할 최종 결과 수
     */
    public AgentSearchResult searchForAgent(String agent, String query, int size) throws Exception {
        // 에이전트 유형에 따라 검색 대상 인덱스와 매뉴얼 도메인 필터를 결정한다.
        List<String> orderIndices = switch (agent) {
            case "delivery" -> List.of("delivery-orders-v1");
            case "refundExchange" -> List.of("refund-exchange-orders-v1");
            default -> List.of();
        };
        List<String> manualDomains = switch (agent) {
            case "delivery" -> List.of("delivery", "common");
            case "refundExchange" -> List.of("refundExchange", "common");
            default -> List.of("common");
        };

        // 질문 텍스트를 벡터(float 배열)로 변환한다.
        // 임베딩 모델이 텍스트의 의미를 1024차원 숫자 공간에 표현하며,
        // 의미가 비슷한 문장일수록 벡터 간 코사인 유사도가 높다.
        float[] queryVector = embeddingModel.embed(query);
        int resultSize = Math.max(1, size);
        // 각 검색 방식에서 충분한 후보를 가져온 뒤 RRF로 줄인다 (resultSize보다 여유 있게 수집)
        int candidateSize = Math.max(resultSize * 4, 20);
        // 문서 ID를 키로 여러 검색 결과를 하나로 합산하기 위한 맵
        Map<String, AgentMergedHit> merged = new LinkedHashMap<>();

        if (!orderIndices.isEmpty()) {
            String orderIndex = orderIndices.getFirst();
            List<String> orderIds = extractOrderIds(agent, query);
            if (!orderIds.isEmpty()) {
                // 주문번호가 질문에 포함된 경우 ES ids 쿼리로 정확히 해당 문서를 가져온다.
                // 키워드/벡터 검색은 순위가 낮을 수 있어 주문번호 문서를 놓칠 수 있기 때문에 별도로 처리한다.
                try {
                    SearchResponse<Map<String, Object>> exact = esClient.search(s -> s
                                    .index(orderIndex)
                                    .size(orderIds.size())
                                    .query(QueryBuilders.ids(ids -> ids.values(orderIds)))
                                    .source(src -> src.filter(f -> f.excludes("embedding"))),  // embedding 벡터는 응답에서 제외 (크고 불필요)
                            MAP_CLASS);
                    mergeAgentExactMatches(merged, exact.hits().hits(), orderIndex);
                } catch (Exception e) {
                    log.warn("Agent exact order search failed. agent={}, index={}, orderIds={}, error={}",
                            agent, orderIndex, orderIds, e.getMessage());
                }
            }
            try {
                // 키워드 검색: BM25 기반으로 단어 일치율이 높은 문서를 찾는다.
                SearchResponse<Map<String, Object>> lexical = esClient.search(s -> s
                        .index(orderIndex).size(candidateSize)
                        .query(QueryBuilders.multiMatch(mm -> mm.query(query).fields("content")))
                        .source(src -> src.filter(f -> f.excludes("embedding"))),
                        MAP_CLASS);
                // 벡터 검색(kNN): 질문 임베딩과 코사인 유사도가 가장 높은 k개의 문서를 찾는다.
                SearchResponse<Map<String, Object>> vector = esClient.search(s -> s
                        .index(orderIndex).size(candidateSize)
                        .knn(knn -> knn.field("embedding")
                                .queryVector(toFloatList(queryVector))
                                .k(candidateSize)
                                .numCandidates(Math.max(candidateSize, 50)))  // numCandidates는 HNSW 탐색 범위, k보다 커야 정확도가 높아진다
                        .source(src -> src.filter(f -> f.excludes("embedding"))),
                        MAP_CLASS);
                mergeAgentRanks(merged, lexical.hits().hits(), orderIndex, ScoreType.LEXICAL);
                mergeAgentRanks(merged, vector.hits().hits(), orderIndex, ScoreType.VECTOR);
            } catch (Exception e) {
                log.warn("Agent order search failed. agent={}, index={}, error={}", agent, orderIndex, e.getMessage());
            }
        }

        List<FieldValue> domainValues = manualDomains.stream().map(FieldValue::of).toList();
        try {
            SearchResponse<Map<String, Object>> lexicalManual = esClient.search(s -> s
                    .index("support-manuals-v1").size(candidateSize)
                    .query(QueryBuilders.bool(b -> b
                            .must(QueryBuilders.multiMatch(mm -> mm.query(query).fields("content")))
                            .filter(QueryBuilders.terms(t -> t.field("domain")
                                    .terms(v -> v.value(domainValues))))))
                    .source(src -> src.filter(f -> f.excludes("embedding"))),
                    MAP_CLASS);
            SearchResponse<Map<String, Object>> vectorManual = esClient.search(s -> s
                    .index("support-manuals-v1").size(candidateSize)
                    .knn(knn -> knn.field("embedding")
                            .queryVector(toFloatList(queryVector))
                            .k(candidateSize)
                            .numCandidates(Math.max(candidateSize, 50))
                            .filter(QueryBuilders.terms(t -> t.field("domain")
                                    .terms(v -> v.value(domainValues)))))
                    .source(src -> src.filter(f -> f.excludes("embedding"))),
                    MAP_CLASS);
            mergeAgentRanks(merged, lexicalManual.hits().hits(), "support-manuals-v1", ScoreType.LEXICAL);
            mergeAgentRanks(merged, vectorManual.hits().hits(), "support-manuals-v1", ScoreType.VECTOR);
        } catch (Exception e) {
            log.warn("Agent manual search failed. agent={}, error={}", agent, e.getMessage());
        }

        List<AgentSearchResult.AgentSearchHitItem> hits = merged.values().stream()
                .map(AgentMergedHit::toResult)
                .sorted(Comparator.comparing(AgentSearchResult.AgentSearchHitItem::score).reversed())
                .limit(resultSize)
                .toList();

        return new AgentSearchResult(query, agent, "app_rrf", RANK_CONSTANT, queryVector.length, hits);
    }

    /**
     * 질문 텍스트에서 정규식으로 주문번호를 추출한다.
     * delivery → DLV-xxxx, refundExchange → RFD-xxxx 형식.
     */
    private List<String> extractOrderIds(String agent, String query) {
        Pattern pattern = switch (agent) {
            case "delivery" -> DELIVERY_ORDER_ID_PATTERN;
            case "refundExchange" -> REFUND_ORDER_ID_PATTERN;
            default -> null;
        };
        if (pattern == null || query == null || query.isBlank()) {
            return List.of();
        }

        List<String> orderIds = new ArrayList<>();
        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            String orderId = matcher.group().toUpperCase();
            if (!orderIds.contains(orderId)) {
                orderIds.add(orderId);
            }
        }
        return orderIds;
    }

    private void mergeAgentExactMatches(
            Map<String, AgentMergedHit> merged,
            List<Hit<Map<String, Object>>> hits,
            String indexName
    ) {
        for (Hit<Map<String, Object>> hit : hits) {
            if (hit.source() == null) continue;
            String key = indexName + "/" + hit.id();
            AgentMergedHit mergedHit = merged.computeIfAbsent(key,
                    k -> new AgentMergedHit(hit.id(), indexName, hit.source()));
            mergedHit.exactMatch = true;
            mergedHit.lexicalScore = maxNullable(mergedHit.lexicalScore, hit.score());
            mergedHit.lexicalRank = minNullable(mergedHit.lexicalRank, 1);
        }
    }

    private void mergeAgentRanks(
            Map<String, AgentMergedHit> merged,
            List<Hit<Map<String, Object>>> hits,
            String indexName,
            ScoreType scoreType
    ) {
        for (int i = 0; i < hits.size(); i++) {
            Hit<Map<String, Object>> hit = hits.get(i);
            if (hit.source() == null) continue;
            String key = indexName + "/" + hit.id();
            AgentMergedHit mergedHit = merged.computeIfAbsent(key,
                    k -> new AgentMergedHit(hit.id(), indexName, hit.source()));
            int rank = i + 1;
            if (scoreType == ScoreType.LEXICAL) {
                mergedHit.lexicalScore = maxNullable(mergedHit.lexicalScore, hit.score());
                mergedHit.lexicalRank = minNullable(mergedHit.lexicalRank, rank);
            } else {
                mergedHit.vectorScore = maxNullable(mergedHit.vectorScore, hit.score());
                mergedHit.vectorRank = minNullable(mergedHit.vectorRank, rank);
            }
        }
    }

    private enum ScoreType {
        LEXICAL,
        VECTOR
    }

    public record AgentSearchResult(
            String query,
            String agent,
            String fusionStrategy,
            int rankConstant,
            int queryEmbeddingDimensions,
            List<AgentSearchHitItem> hits
    ) {
        public record AgentSearchHitItem(
                String id,
                String indexName,
                Double score,
                Double lexicalScore,
                Double vectorScore,
                Integer lexicalRank,
                Integer vectorRank,
                Map<String, Object> source
        ) {
        }
    }

    private static class AgentMergedHit {
        private final String id;
        private final String indexName;
        private final Map<String, Object> source;
        private Double lexicalScore;
        private Double vectorScore;
        private Integer lexicalRank;
        private Integer vectorRank;
        private boolean exactMatch;

        private AgentMergedHit(String id, String indexName, Map<String, Object> source) {
            this.id = id;
            this.indexName = indexName;
            this.source = source;
        }

        /**
         * RRF 점수를 계산해 최종 AgentSearchHitItem을 반환한다.
         * score = (1/(k+lexicalRank)) * lexicalWeight
         *       + (1/(k+vectorRank))  * vectorWeight
         *       + (exactMatch ? bonus : 0)
         */
        private AgentSearchResult.AgentSearchHitItem toResult() {
            double score = (rrfScore(lexicalRank) * LEXICAL_WEIGHT)
                    + (rrfScore(vectorRank) * VECTOR_WEIGHT)
                    + (exactMatch ? EXACT_MATCH_BONUS : 0.0);
            return new AgentSearchResult.AgentSearchHitItem(
                    id, indexName, score, lexicalScore, vectorScore, lexicalRank, vectorRank, source);
        }

        // RRF 핵심 공식. rank가 null이면(해당 검색 방식에서 결과에 없었음) 0점 처리한다.
        private double rrfScore(Integer rank) {
            if (rank == null) return 0.0;
            return 1.0 / (RANK_CONSTANT + rank);
        }
    }
}
