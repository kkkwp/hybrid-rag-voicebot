package com.example.backend.tts;

import com.example.backend.common.exception.AppException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.tts.TtsProperties;
import com.example.backend.tts.dto.TtsAudioResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class TtsClient {

    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);
    private static final int LOG_BODY_TAIL_LIMIT = 512;

    private final ObjectMapper objectMapper;
    private final TtsProperties properties;
    private final HttpClient httpClient;

    public TtsClient(ObjectMapper objectMapper, TtsProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();
    }

    public TtsAudioResponse synthesize(String text) {
        String requestBody = requestBody(text);
        HttpRequest request = HttpRequest.newBuilder(synthesizeUri())
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("TTS request failed. status={} bodyTail={}", response.statusCode(), tail(response.body()));
                throw new AppException(ErrorCode.TTS_REQUEST_FAILED);
            }
            if (!contentType.toLowerCase().startsWith("audio/")) {
                log.warn("TTS request returned non-audio content. status={} contentType={}", response.statusCode(), contentType);
                throw new AppException(ErrorCode.TTS_RESPONSE_NOT_AUDIO);
            }

            return new TtsAudioResponse(response.body(), contentType);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.TTS_REQUEST_INTERRUPTED, error);
        } catch (IOException error) {
            throw new AppException(ErrorCode.TTS_CALL_FAILED, error);
        }
    }

    private URI synthesizeUri() {
        String baseUrl = properties.baseUrl();
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + "/synthesize");
    }

    private String requestBody(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("text", text);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            throw new AppException(ErrorCode.TTS_REQUEST_SERIALIZE_FAILED, error);
        }
    }

    private String tail(byte[] value) {
        if (value == null || value.length == 0) {
            return "";
        }
        String text = new String(value);
        if (text.length() <= LOG_BODY_TAIL_LIMIT) {
            return text;
        }
        return text.substring(text.length() - LOG_BODY_TAIL_LIMIT);
    }
}
