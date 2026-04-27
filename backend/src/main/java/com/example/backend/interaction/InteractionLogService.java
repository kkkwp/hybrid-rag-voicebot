package com.example.backend.interaction;

import com.example.backend.voice.TranscriptionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class InteractionLogService {

    private final ObjectMapper objectMapper;
    private final Path textLogPath;
    private final Path voiceLogPath;

    public InteractionLogService(
            ObjectMapper objectMapper,
            @Value("${app.interaction-log.text-path:./log/text-interactions.jsonl}") String textLogPath,
            @Value("${app.interaction-log.voice-path:./log/voice-interactions.jsonl}") String voiceLogPath
    ) {
        this.objectMapper = objectMapper;
        this.textLogPath = Path.of(textLogPath);
        this.voiceLogPath = Path.of(voiceLogPath);
    }

    public void logText(String requestBody, int n8nStatus, String responseBody) {
        ObjectNode event = baseEvent("text");
        event.set("request", parseOrText(requestBody));
        event.put("n8nStatus", n8nStatus);
        event.set("response", parseOrText(responseBody));
        append(textLogPath, event);
    }

    public void logVoice(
            TranscriptionResult transcription,
            Map<String, Object> answer,
            List<String> agents,
            Integer size,
            long elapsedMillis
    ) {
        ObjectNode event = baseEvent("voice");
        ObjectNode transcriptionNode = event.putObject("transcription");
        String text = transcription == null || transcription.text() == null ? "" : transcription.text().trim();
        transcriptionNode.put("textLength", text.length());
        transcriptionNode.put("language", transcription == null ? "" : transcription.language());
        transcriptionNode.put("durationSeconds", transcription == null ? 0.0 : transcription.durationSeconds());
        event.set("agents", objectMapper.valueToTree(agents));
        if (size != null) {
            event.put("size", size);
        }
        event.put("elapsedMillis", elapsedMillis);
        event.set("response", answer == null ? objectMapper.nullNode() : objectMapper.valueToTree(answer));
        append(voiceLogPath, event);
    }

    private ObjectNode baseEvent(String channel) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("loggedAt", Instant.now().toString());
        event.put("channel", channel);
        return event;
    }

    private JsonNode parseOrText(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (IOException ignored) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("raw", value);
            return node;
        }
    }

    private synchronized void append(Path path, ObjectNode event) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new InteractionLogException("failed to write interaction log: " + path, error);
        }
    }
}
