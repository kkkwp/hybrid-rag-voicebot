package com.example.backend.stt.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TranscriptionResult(
        String text,
        String language,
        @JsonProperty("duration") double durationSeconds,
        List<Segment> segments
) {
    public record Segment(
            double start,
            double end,
            String text
    ) {
    }
}
