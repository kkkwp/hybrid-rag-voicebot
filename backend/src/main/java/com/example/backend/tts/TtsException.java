package com.example.backend.tts;

public class TtsException extends RuntimeException {

    private final int statusCode;

    public TtsException(String message) {
        this(message, -1, null);
    }

    public TtsException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public TtsException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public TtsException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
