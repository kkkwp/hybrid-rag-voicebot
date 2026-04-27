package com.example.backend.agent.dto;

import com.example.backend.search.dto.AgentSearchResult;

import java.util.List;

public record AgentResult(
        String agent,
        boolean failed,
        String answer,
        int evidenceCount,
        List<AgentSearchResult.AgentSearchHitItem> evidence,
        String fusionStrategy,
        int rankConstant,
        long elapsedMillis,
        String error
) {
    public static AgentResult failed(String agent, String error) {
        return new AgentResult(agent, true, "", 0, List.of(), null, 0, 0, error);
    }
}
