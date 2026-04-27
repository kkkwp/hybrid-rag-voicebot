package com.example.backend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Arrays;
import java.util.List;

@RestController
public class VoiceAskController {

    private static final Logger log = LoggerFactory.getLogger(VoiceAskController.class);

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

    @ExceptionHandler(VoiceAskException.class)
    public ResponseEntity<VoiceAskErrorResponse> handleVoiceAskException(VoiceAskException error) {
        return error(error.statusCode(), "voice", error.getMessage());
    }

    @ExceptionHandler(WhisperException.class)
    public ResponseEntity<VoiceAskErrorResponse> handleWhisperException(WhisperException error) {
        log.warn("Voice ask failed at whisper stage. statusCode={} message={}", error.statusCode(), error.getMessage());
        return error(HttpStatus.BAD_GATEWAY.value(), "whisper", error.getMessage());
    }

    @ExceptionHandler(N8nWebhookException.class)
    public ResponseEntity<VoiceAskErrorResponse> handleN8nWebhookException(N8nWebhookException error) {
        log.warn("Voice ask failed at n8n stage. statusCode={} message={}", error.statusCode(), error.getMessage(), error.getCause());
        return error(HttpStatus.BAD_GATEWAY.value(), "n8n", error.getMessage());
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<VoiceAskErrorResponse> handleMultipartException(Exception error) {
        return error(HttpStatus.BAD_REQUEST.value(), "voice", error.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<VoiceAskErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException error) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "voice", "audio payload too large");
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

    private ResponseEntity<VoiceAskErrorResponse> error(int statusCode, String stage, String message) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new VoiceAskErrorResponse(stage, status.value(), message));
    }

    public record VoiceAskErrorResponse(
            String stage,
            int status,
            String message
    ) {
    }
}
