package com.example.backend.tts;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.tts")
public record TtsProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        int timeoutSeconds,
        int maxTextChars,
        String model,
        String voice,
        String responseFormat
) {
}
