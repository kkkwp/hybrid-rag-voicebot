package com.example.backend.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProgressEventService {

    private static final Logger log = LoggerFactory.getLogger(ProgressEventService.class);
    private static final long SSE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ProgressEventService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter register(String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError(e -> emitters.remove(sessionId));
        emitters.put(sessionId, emitter);
        return emitter;
    }

    public void emit(String sessionId, String step, String message) {
        if (sessionId == null || sessionId.isBlank()) return;
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) return;
        try {
            String data = objectMapper.writeValueAsString(
                    Map.of("step", step, "message", message, "ts", Instant.now().toEpochMilli()));
            emitter.send(SseEmitter.event().name("progress").data(data));
        } catch (IOException e) {
            log.debug("SSE emit failed. sessionId={}, step={}", sessionId, step);
            emitters.remove(sessionId);
        }
    }

    public void complete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("complete").data("done"));
            emitter.complete();
        } catch (IOException e) {
            // ignore
        }
    }

    public void error(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) return;
        SseEmitter emitter = emitters.remove(sessionId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (IOException e) {
            // ignore
        }
    }
}
