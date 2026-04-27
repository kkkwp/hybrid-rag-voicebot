package com.example.backend.agent.dto;

public record MultiAgentMetadata(
        String mainModel,
        String executionStrategy,
        long elapsedMillis,
        boolean aggregatorSkipped
) {
}
