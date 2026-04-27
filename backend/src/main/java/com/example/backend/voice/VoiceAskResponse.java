package com.example.backend.voice;

import java.util.Map;

public record VoiceAskResponse(
        TranscriptionResult transcription,
        Map<String, Object> answer
) {
}
