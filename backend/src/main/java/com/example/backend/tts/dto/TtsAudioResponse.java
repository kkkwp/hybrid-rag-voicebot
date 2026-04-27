package com.example.backend.tts.dto;

public record TtsAudioResponse(
        byte[] audioBytes,
        String contentType
) {
}
