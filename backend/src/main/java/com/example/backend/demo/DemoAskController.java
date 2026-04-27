package com.example.backend.demo;

import com.example.backend.interaction.InteractionLogService;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RestController
public class DemoAskController {

    private static final Logger log = LoggerFactory.getLogger(DemoAskController.class);

    private final String webhookUrl;
    private final InteractionLogService interactionLogService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public DemoAskController(
            @Value("${app.demo.n8n-webhook-url:http://localhost:5678/webhook-test/consultation-multi-agent}") String webhookUrl,
            InteractionLogService interactionLogService
    ) {
        this.webhookUrl = webhookUrl;
        this.interactionLogService = interactionLogService;
    }

    @PostMapping(value = "/demo/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    public String ask(@RequestBody String requestBody) throws Exception {
        log.info("Demo ask received. forwarding raw body to n8n test webhook. webhookUrl={}, body={}", webhookUrl, requestBody);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        log.info("n8n webhook response received. status={}", response.statusCode());
        interactionLogService.logText(requestBody, response.statusCode(), response.body());
        if (response.statusCode() >= 400) {
            return """
                    {"error":"n8n webhook request failed","status":%d,"body":%s}
                    """.formatted(response.statusCode(), jsonString(response.body()));
        }
        return response.body();
    }

    private String jsonString(String value) {
        if (value == null) {
            value = "";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
