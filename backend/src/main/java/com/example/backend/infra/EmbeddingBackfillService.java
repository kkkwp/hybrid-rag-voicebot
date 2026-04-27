package com.example.backend.infra;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ES 인덱스에서 embedding 필드가 없는 문서를 찾아 임베딩을 생성·저장하는 백필(backfill) 서비스.
 *
 * 임베딩(embedding)이란 텍스트를 숫자 벡터로 변환한 표현이다.
 * 데이터 시드 스크립트로 문서를 삽입할 때 embedding 없이 저장될 수 있으므로,
 * 이 서비스가 주기적으로 실행되며 누락된 임베딩을 채워준다.
 * 임베딩이 있어야 벡터 검색(kNN)이 동작한다.
 */
@Service
public class EmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);
    private static final List<String> INDEX_NAMES = List.of(
            "delivery-orders-v1",
            "refund-exchange-orders-v1",
            "support-manuals-v1"
    );
    private static final int BATCH_SIZE = 20;  // 한 번에 처리할 문서 수. 임베딩 모델 호출은 비용이 있으므로 소량씩 처리

    private final ElasticsearchClient esClient;
    private final EmbeddingModel embeddingModel;

    public EmbeddingBackfillService(ElasticsearchClient esClient, EmbeddingModel embeddingModel) {
        this.esClient = esClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 앱 시작 5초 후 최초 실행되고, 이후 30초마다 반복 실행된다. (application.yaml에서 변경 가능)
     * 각 인덱스를 순서대로 순회하며 embedding 필드가 없는 문서에 임베딩을 생성해 저장한다.
     */
    @Scheduled(initialDelayString = "${app.embedding-backfill.initial-delay:5000}",
            fixedDelayString = "${app.embedding-backfill.fixed-delay:30000}")
    public void backfillMissingEmbeddings() throws Exception {
        for (String indexName : INDEX_NAMES) {
            backfillIndex(indexName);
        }
    }

    private void backfillIndex(String indexName) throws Exception {
        boolean exists = esClient.indices()
                .exists(r -> r.index(indexName))
                .value();
        if (!exists) {
            return;
        }

        // embedding 필드가 없는(mustNot exists) 문서를 최대 BATCH_SIZE개 조회한다.
        // content 필드만 가져오면 충분하다 — embedding 생성에 다른 필드는 필요 없다.
        SearchResponse<Map> response = esClient.search(s -> s
                        .index(indexName)
                        .size(BATCH_SIZE)
                        .query(q -> q
                                .bool(b -> b
                                        .mustNot(m -> m.exists(e -> e.field("embedding")))))
                        .source(src -> src.filter(f -> f.includes("content"))),
                Map.class);

        int count = 0;
        for (var hit : response.hits().hits()) {
            String content = content(hit.source());
            if (content.isBlank()) {
                continue;
            }

            float[] embedding = embeddingModel.embed(content);
            esClient.update(u -> u
                            .index(indexName)
                            .id(hit.id())
                            .doc(new EmbeddingUpdateDocument(embedding)),
                    Void.class);
            count++;
        }
        if (count > 0) {
            log.info("embedding backfill 완료: index={}, count={}", indexName, count);
        }
    }

    private String content(Map document) {
        if (document == null) {
            return "";
        }
        Object value = document.get("content");
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    private record EmbeddingUpdateDocument(float[] embedding) {
    }
}
