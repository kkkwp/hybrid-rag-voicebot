package com.example.backend.voice;

public class VoiceAskException extends RuntimeException {

    private final int statusCode;

    public VoiceAskException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public VoiceAskException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
