package com.example.backend.agent.dto;

import java.util.List;

public record MultiAgentAnswerResponse(
        String question,
        List<String> agents,
        String finalAnswer,
        List<AgentResult> agentResults,
        MultiAgentMetadata metadata
) {
}
