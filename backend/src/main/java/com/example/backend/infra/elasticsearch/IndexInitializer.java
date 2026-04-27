package com.example.backend.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.analysis.NoriDecompoundMode;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 필요한 Elasticsearch 인덱스를 자동으로 생성한다.
 *
 * 인덱스(index)는 RDB의 테이블에 해당하는 개념이다.
 * 각 인덱스는 매핑(mapping, 컬럼 스키마)과 분석기(analyzer, 텍스트 처리 방식)를 가진다.
 *
 * 벡터 검색을 위해 embedding 필드를 dense_vector 타입으로 정의한다.
 * 코사인 유사도(Cosine)를 사용하며, 임베딩 차원은 사용 중인 모델(bge-m3)에 맞춰 1024로 고정한다.
 *
 * 한국어 형태소 분석기 Nori를 설정한다.
 * Nori는 "배송중"을 "배송"+"중"으로 분리해 주어 한국어 키워드 검색 정확도를 높인다.
 */
@Component
public class IndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);
    private static final String DELIVERY_ORDERS_INDEX = "delivery-orders-v1";
    private static final String REFUND_EXCHANGE_ORDERS_INDEX = "refund-exchange-orders-v1";
    private static final String SUPPORT_MANUALS_INDEX = "support-manuals-v1";

    private final ElasticsearchClient esClient;

    public IndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) throws Exception {
        createOrderIndexIfMissing(DELIVERY_ORDERS_INDEX);
        createOrderIndexIfMissing(REFUND_EXCHANGE_ORDERS_INDEX);
        createSupportManualsIndexIfMissing();
    }

    private void createOrderIndexIfMissing(String indexName) throws Exception {
        boolean exists = esClient.indices()
                .exists(r -> r.index(indexName))
                .value();
        if (exists) {
            return;
        }

        esClient.indices().create(r -> r
                .index(indexName)
                .settings(s -> koreanAnalysis(s))
                .mappings(m -> m
                        .properties("orderId", p -> p.keyword(k -> k))
                        .properties("customerName", p -> p.keyword(k -> k))
                        .properties("status", p -> p.keyword(k -> k))
                        .properties("location", p -> p.text(t -> t.analyzer("korean")
                                .fields("keyword", f -> f.keyword(k -> k))))
                        .properties("expectedDate", p -> p.date(d -> d))
                        .properties("requestType", p -> p.keyword(k -> k))
                        .properties("content", p -> p.text(t -> t.analyzer("korean")))
                        .properties("updatedAt", p -> p.date(d -> d))
                        .properties("embedding", p -> p.denseVector(d -> d
                                .dims(1024)
                                .index(true)
                                .similarity(DenseVectorSimilarity.Cosine))))
        );
        log.info("{} 인덱스 생성 완료", indexName);
    }

    private void createSupportManualsIndexIfMissing() throws Exception {
        boolean exists = esClient.indices()
                .exists(r -> r.index(SUPPORT_MANUALS_INDEX))
                .value();
        if (exists) {
            return;
        }

        esClient.indices().create(r -> r
                .index(SUPPORT_MANUALS_INDEX)
                .settings(s -> koreanAnalysis(s))
                .mappings(m -> m
                        .properties("manualId", p -> p.keyword(k -> k))
                        .properties("domain", p -> p.keyword(k -> k))
                        .properties("situation", p -> p.text(t -> t.analyzer("korean")
                                .fields("keyword", f -> f.keyword(k -> k))))
                        .properties("title", p -> p.text(t -> t.analyzer("korean")
                                .fields("keyword", f -> f.keyword(k -> k))))
                        .properties("content", p -> p.text(t -> t.analyzer("korean")))
                        .properties("recommendedResponse", p -> p.text(t -> t.analyzer("korean")))
                        .properties("tags", p -> p.keyword(k -> k))
                        .properties("updatedAt", p -> p.date(d -> d))
                        .properties("embedding", p -> p.denseVector(d -> d
                                .dims(1024)
                                .index(true)
                                .similarity(DenseVectorSimilarity.Cosine))))
        );
        log.info("{} 인덱스 생성 완료", SUPPORT_MANUALS_INDEX);
    }

    /**
     * 한국어 Nori 분석기를 인덱스 설정에 추가한다.
     *
     * decompoundMode=Mixed: 복합어를 원형과 분리형 모두 색인한다.
     *   예) "배송중" → "배송중", "배송", "중" 모두 색인
     *   → "배송"으로 검색해도 "배송중" 문서를 찾을 수 있다.
     * nori_part_of_speech 필터: 조사·어미 같은 불필요한 품사를 제거한다.
     * lowercase 필터: 영문 혼용 텍스트의 대소문자 통일.
     */
    private co.elastic.clients.elasticsearch.indices.IndexSettings.Builder koreanAnalysis(
            co.elastic.clients.elasticsearch.indices.IndexSettings.Builder settings
    ) {
        return settings.analysis(a -> a
                .tokenizer("korean_nori_tokenizer", t -> t
                        .definition(d -> d.noriTokenizer(n ->
                                n.decompoundMode(NoriDecompoundMode.Mixed))))
                .analyzer("korean", an -> an
                        .custom(c -> c
                                .tokenizer("korean_nori_tokenizer")
                                .filter("nori_part_of_speech", "lowercase"))));
    }
}
