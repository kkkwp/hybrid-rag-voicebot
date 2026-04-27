package com.example.backend.conversation.controller;

import com.example.backend.conversation.ConversationAskService;
import com.example.backend.conversation.dto.ConversationAskResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

@RestController
public class ConversationVoiceAskController {

    private final ConversationAskService conversationAskService;

    public ConversationVoiceAskController(ConversationAskService conversationAskService) {
        this.conversationAskService = conversationAskService;
    }

    @PostMapping(value = "/voice/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConversationAskResponse ask(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam(value = "agents", required = false) String agentsCsv,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return conversationAskService.ask(audio, parseAgents(agentsCsv), size);
    }

    private List<String> parseAgents(String agentsCsv) {
        if (agentsCsv == null || agentsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(agentsCsv.split(","))
                .map(String::trim)
                .filter(agent -> !agent.isBlank())
                .toList();
    }
}
