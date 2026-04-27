package com.example.backend.common;

public enum ErrorCode {

    // ── 음성 입력 ──────────────────────────────────────────────────────────────
    EMPTY_AUDIO(400, "voice", "음성 파일이 없습니다"),
    INVALID_AUDIO_FORMAT(400, "voice", "지원하지 않는 오디오 형식입니다"),
    AUDIO_TOO_LARGE(413, "voice", "음성 파일이 너무 큽니다"),
    AUDIO_READ_FAILED(400, "voice", "음성 파일을 읽을 수 없습니다"),
    EMPTY_TRANSCRIPTION(400, "voice", "음성 인식 결과가 비어 있습니다"),
    MISSING_AUDIO_PART(400, "voice", "음성 파일이 포함되지 않은 요청입니다"),

    // ── Whisper ────────────────────────────────────────────────────────────────
    WHISPER_UNAVAILABLE(502, "whisper", "음성 인식 서비스에 연결할 수 없습니다"),

    // ── n8n ───────────────────────────────────────────────────────────────────
    N8N_UNAVAILABLE(502, "n8n", "워크플로우 서비스에 연결할 수 없습니다"),

    // ── TTS ───────────────────────────────────────────────────────────────────
    TTS_DISABLED(503, "tts", "음성 합성 서비스가 비활성화되어 있습니다"),
    EMPTY_TTS_INPUT(400, "tts", "합성할 텍스트가 없습니다"),
    TTS_INPUT_TOO_LARGE(413, "tts", "텍스트가 너무 깁니다"),
    TTS_UNAVAILABLE(502, "tts", "음성 합성 서비스에 연결할 수 없습니다"),
    TTS_RESPONSE_INVALID(502, "tts", "음성 합성 서비스 응답이 올바르지 않습니다");

    private final int status;
    private final String stage;
    private final String message;

    ErrorCode(int status, String stage, String message) {
        this.status = status;
        this.stage = stage;
        this.message = message;
    }

    public int status() {
        return status;
    }

    public String stage() {
        return stage;
    }

    public String message() {
        return message;
    }
}
