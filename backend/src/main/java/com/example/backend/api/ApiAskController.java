package com.example.backend.api;

import com.example.backend.common.exception.AppException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.log.InteractionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RestController
public class ApiAskController {

    private static final Logger log = LoggerFactory.getLogger(ApiAskController.class);
    private static final int LOG_BODY_TAIL_LIMIT = 512;

    private final String webhookUrl;
    private final InteractionLogService interactionLogService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ApiAskController(
            @Value("${app.api.n8n-webhook-url:http://localhost:5678/webhook-test/consultation-multi-agent}") String webhookUrl,
            InteractionLogService interactionLogService
    ) {
        this.webhookUrl = webhookUrl;
        this.interactionLogService = interactionLogService;
    }

    @PostMapping(value = "/api/ask", produces = MediaType.APPLICATION_JSON_VALUE)
    public String ask(@RequestBody String requestBody) {
        log.info("API ask received. forwarding raw body to n8n test webhook. webhookUrl={}, body={}", webhookUrl, requestBody);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("n8n webhook response received. status={}", response.statusCode());
            interactionLogService.logText(requestBody, response.statusCode(), response.body());
            if (response.statusCode() >= 400) {
                log.warn(
                        "n8n webhook returned non-2xx. status={} bodyTail={}",
                        response.statusCode(),
                        tail(response.body())
                );
                throw new AppException(ErrorCode.API_WEBHOOK_REQUEST_FAILED);
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.API_WEBHOOK_REQUEST_INTERRUPTED, error);
        } catch (IllegalArgumentException error) {
            throw new AppException(ErrorCode.API_WEBHOOK_INVALID_URL, error);
        } catch (IOException error) {
            throw new AppException(ErrorCode.API_WEBHOOK_CALL_FAILED, error);
        }
    }

    private String tail(String value) {
        if (value == null || value.length() <= LOG_BODY_TAIL_LIMIT) {
            return value;
        }
        return value.substring(value.length() - LOG_BODY_TAIL_LIMIT);
    }
}
