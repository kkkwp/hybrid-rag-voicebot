package com.example.backend.voice.api.dto;

import com.example.backend.voice.stt.dto.TranscriptionResult;

import java.util.Map;

public record VoiceAskResponse(
        TranscriptionResult transcription,
        Map<String, Object> answer
) {
}
