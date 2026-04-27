package com.example.backend.tts;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.tts")
public record TtsProperties(
        boolean enabled,
        String baseUrl,
        int timeoutSeconds,
        int maxTextChars
) {
}
