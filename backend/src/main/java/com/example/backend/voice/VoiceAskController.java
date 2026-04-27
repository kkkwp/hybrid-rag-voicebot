package com.example.backend.voice;

import com.example.backend.voice.dto.VoiceAskResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

@RestController
public class VoiceAskController {

    private final VoiceAskService voiceAskService;

    public VoiceAskController(VoiceAskService voiceAskService) {
        this.voiceAskService = voiceAskService;
    }

    @PostMapping(value = "/voice/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceAskResponse ask(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam(value = "agents", required = false) String agentsCsv,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return voiceAskService.ask(audio, parseAgents(agentsCsv), size);
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
