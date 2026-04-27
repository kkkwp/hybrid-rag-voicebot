package com.example.backend.stt;

import com.example.backend.common.exception.AppException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.stt.SttProperties;
import com.example.backend.stt.dto.TranscriptionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Component
public class WhisperClient {

    private static final Logger log = LoggerFactory.getLogger(WhisperClient.class);
    private static final int LOG_BODY_TAIL_LIMIT = 512;

    private final ObjectMapper objectMapper;
    private final SttProperties properties;
    private final HttpClient httpClient;

    public WhisperClient(ObjectMapper objectMapper, SttProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.whisperTimeoutSeconds()))
                .build();
    }

    public TranscriptionResult transcribe(byte[] audioBytes, String originalFilename) {
        Objects.requireNonNull(audioBytes, "audioBytes must not be null");

        String boundary = "----spring-ai-voice-" + randomHex();
        byte[] body = multipartBody(boundary, audioBytes, originalFilename);
        HttpRequest request = HttpRequest.newBuilder(transcribeUri())
                .timeout(Duration.ofSeconds(properties.whisperTimeoutSeconds()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "Whisper transcription failed. status={} bodyTail={}",
                        response.statusCode(),
                        tail(response.body())
                );
                throw new AppException(ErrorCode.WHISPER_REQUEST_FAILED);
            }
            return objectMapper.readValue(response.body(), TranscriptionResult.class);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.WHISPER_REQUEST_INTERRUPTED, error);
        } catch (JsonProcessingException error) {
            throw new AppException(ErrorCode.WHISPER_RESPONSE_PARSE_FAILED, error);
        } catch (IOException error) {
            throw new AppException(ErrorCode.WHISPER_CALL_FAILED, error);
        }
    }

    private URI transcribeUri() {
        String baseUrl = properties.whisperBaseUrl();
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBaseUrl + "/transcribe");
    }

    private byte[] multipartBody(String boundary, byte[] audioBytes, String originalFilename) {
        String filename = safeFilename(originalFilename);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(output, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
        writeAscii(output, "Content-Type: application/octet-stream\r\n\r\n");
        output.writeBytes(audioBytes);
        writeAscii(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String safeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "input.webm";
        }
        return originalFilename
                .replace("\\", "_")
                .replace("/", "_")
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_");
    }

    private String randomHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String tail(String value) {
        if (value == null || value.length() <= LOG_BODY_TAIL_LIMIT) {
            return value;
        }
        return value.substring(value.length() - LOG_BODY_TAIL_LIMIT);
    }
}
