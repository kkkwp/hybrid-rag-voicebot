package com.example.backend.agent.dto;

import java.util.List;

public record MultiAgentAnswerRequest(
        String question,
        List<String> agents,
        Integer size
) {
    public int sizeOrDefault() {
        if (size == null) return 2;
        return Math.max(1, Math.min(size, 10));
    }
}
