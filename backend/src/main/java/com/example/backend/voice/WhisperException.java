package com.example.backend.voice;

public class WhisperException extends RuntimeException {

    private final int statusCode;

    public WhisperException(String message) {
        this(message, -1, null);
    }

    public WhisperException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public WhisperException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    private WhisperException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
