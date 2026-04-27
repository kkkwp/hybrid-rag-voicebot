package com.example.backend.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.voice")
public record ConversationWebhookProperties(
        String n8nWebhookUrl,
        int n8nTimeoutSeconds
) {
}
