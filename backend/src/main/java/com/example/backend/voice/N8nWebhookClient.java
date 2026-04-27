package com.example.backend.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class N8nWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(N8nWebhookClient.class);
    private static final int LOG_BODY_TAIL_LIMIT = 512;

    private final ObjectMapper objectMapper;
    private final VoiceAskProperties properties;
    private final HttpClient httpClient;

    public N8nWebhookClient(ObjectMapper objectMapper, VoiceAskProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(properties.n8nTimeoutSeconds()))
                .build();
    }

    public Map<String, Object> ask(String question, List<String> agents, Integer size) {
        String requestBody = requestBody(question, agents, size);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.n8nWebhookUrl()))
                .timeout(Duration.ofSeconds(properties.n8nTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("n8n voice webhook response received. status={}", response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "n8n voice webhook failed. status={} bodyTail={}",
                        response.statusCode(),
                        tail(response.body())
                );
                throw new N8nWebhookException("n8n webhook request failed", response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new N8nWebhookException("n8n webhook request interrupted", error);
        } catch (JsonProcessingException error) {
            throw new N8nWebhookException("failed to parse n8n webhook response", error);
        } catch (IOException error) {
            throw new N8nWebhookException("failed to call n8n webhook", error);
        }
    }

    private String requestBody(String question, List<String> agents, Integer size) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("question", question);
        if (size != null) {
            root.put("size", size);
        }
        if (agents != null && !agents.isEmpty()) {
            ArrayNode agentsNode = root.putArray("agents");
            agents.forEach(agentsNode::add);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException error) {
            throw new N8nWebhookException("failed to serialize n8n webhook request", error);
        }
    }

    private String tail(String value) {
        if (value == null || value.length() <= LOG_BODY_TAIL_LIMIT) {
            return value;
        }
        return value.substring(value.length() - LOG_BODY_TAIL_LIMIT);
    }
}
