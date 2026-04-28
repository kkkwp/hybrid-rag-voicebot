package com.example.backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class CustomerNameCache {

    private static final Logger log = LoggerFactory.getLogger(CustomerNameCache.class);

    private final ElasticsearchClient esClient;
    private volatile List<String> names = List.of();

    public CustomerNameCache(ElasticsearchClient esClient) {
        this.esClient = esClient;
        refresh();
    }

    @Scheduled(fixedDelay = 1800000)
    public void refresh() {
        try {
            var response = esClient.search(s -> s
                    .index("delivery-orders-v1", "refund-exchange-orders-v1")
                    .size(0)
                    .aggregations("names", a -> a
                            .terms(t -> t.field("customerName").size(10000))),
                    Void.class);

            List<String> loaded = response.aggregations()
                    .get("names")
                    .sterms()
                    .buckets().array().stream()
                    .map(b -> b.key().stringValue())
                    .filter(name -> name != null && !name.isBlank())
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();

            this.names = loaded;
            log.info("CustomerNameCache refreshed. count={}", loaded.size());
        } catch (Exception e) {
            log.warn("CustomerNameCache refresh failed. error={}", e.getMessage());
        }
    }

    public String findInQuery(String query) {
        if (query == null || query.isBlank()) return null;
        return names.stream()
                .filter(query::contains)
                .findFirst()
                .orElse(null);
    }
}
