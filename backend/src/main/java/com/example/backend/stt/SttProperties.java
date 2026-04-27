package com.example.backend.stt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.voice")
public record SttProperties(
        String whisperBaseUrl,
        int whisperTimeoutSeconds,
        long maxAudioBytes
) {
}
