
package com.example.backend.progress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@RestController
public class ProgressEventController {

    private final ProgressEventService progressEventService;
    private final String n8nHealthUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ProgressEventController(
            ProgressEventService progressEventService,
            @Value("${app.n8n.health-url:http://n8n:5678/healthz}") String n8nHealthUrl
    ) {
        this.progressEventService = progressEventService;
        this.n8nHealthUrl = n8nHealthUrl;
    }

    @GetMapping(value = "/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId) {
        return progressEventService.register(sessionId);
    }

    @GetMapping("/n8n/status")
    public Map<String, Object> n8nStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(n8nHealthUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            return Map.of("status", up ? "up" : "down", "code", response.statusCode());
        } catch (Exception e) {
            return Map.of("status", "down", "message", e.getMessage() != null ? e.getMessage() : "연결 실패");
        }
    }
}
