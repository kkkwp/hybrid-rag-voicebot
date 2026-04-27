package com.example.backend.search.dto;

import java.util.List;
import java.util.Map;

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
