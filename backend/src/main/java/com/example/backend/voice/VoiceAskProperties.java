package com.example.backend.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.voice")
public record VoiceAskProperties(
        String whisperBaseUrl,
        int whisperTimeoutSeconds,
        long maxAudioBytes,
        String n8nWebhookUrl,
        int n8nTimeoutSeconds
) {
}
