package com.example.backend.conversation.dto;

import com.example.backend.stt.dto.TranscriptionResult;

import java.util.Map;

public record ConversationAskResponse(
        TranscriptionResult transcription,
        Map<String, Object> answer
) {
}
