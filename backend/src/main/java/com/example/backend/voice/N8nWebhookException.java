package com.example.backend.voice;

public class N8nWebhookException extends RuntimeException {

    private final int statusCode;

    public N8nWebhookException(String message) {
        this(message, -1, null);
    }

    public N8nWebhookException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public N8nWebhookException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    private N8nWebhookException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
