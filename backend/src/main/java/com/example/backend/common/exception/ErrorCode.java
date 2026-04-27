package com.example.backend.common.exception;

public enum ErrorCode {
    // voice
    VOICE_EMPTY_AUDIO("voice", 400, "오디오 파일이 비어 있습니다."),
    VOICE_CONTENT_TYPE_REQUIRED("voice", 400, "오디오 형식 파일만 업로드할 수 있습니다."),
    VOICE_AUDIO_TOO_LARGE("voice", 413, "오디오 파일 크기가 제한을 초과했습니다."),
    VOICE_TRANSCRIPTION_EMPTY("voice", 400, "음성 인식 결과가 비어 있습니다."),
    VOICE_AUDIO_READ_FAILED("voice", 400, "업로드된 오디오를 읽는 데 실패했습니다."),
    VOICE_MULTIPART_INVALID("voice", 400, "오디오 파일 파라미터가 올바르지 않습니다."),

    // whisper
    WHISPER_REQUEST_FAILED("whisper", 502, "음성 인식 서비스 요청에 실패했습니다."),
    WHISPER_REQUEST_INTERRUPTED("whisper", 502, "음성 인식 요청이 중단되었습니다."),
    WHISPER_RESPONSE_PARSE_FAILED("whisper", 502, "음성 인식 응답을 해석하지 못했습니다."),
    WHISPER_CALL_FAILED("whisper", 502, "음성 인식 서비스 호출에 실패했습니다."),

    // n8n webhook
    N8N_WEBHOOK_REQUEST_FAILED("n8n", 502, "n8n 웹훅 요청에 실패했습니다."),
    N8N_WEBHOOK_REQUEST_INTERRUPTED("n8n", 502, "n8n 웹훅 요청이 중단되었습니다."),
    N8N_WEBHOOK_RESPONSE_PARSE_FAILED("n8n", 502, "n8n 응답을 해석하지 못했습니다."),
    N8N_WEBHOOK_CALL_FAILED("n8n", 502, "n8n 웹훅 호출에 실패했습니다."),
    N8N_WEBHOOK_REQUEST_SERIALIZE_FAILED("n8n", 500, "n8n 요청 본문 생성에 실패했습니다."),

    // tts
    TTS_DISABLED("tts", 503, "음성 합성 기능이 비활성화되어 있습니다."),
    TTS_TEXT_REQUIRED("tts", 400, "음성 합성할 텍스트가 필요합니다."),
    TTS_TEXT_TOO_LARGE("tts", 413, "텍스트 길이가 제한을 초과했습니다."),
    TTS_REQUEST_FAILED("tts", 502, "음성 합성 요청에 실패했습니다."),
    TTS_RESPONSE_NOT_AUDIO("tts", 502, "음성 합성 응답이 오디오 형식이 아닙니다."),
    TTS_REQUEST_INTERRUPTED("tts", 502, "음성 합성 요청이 중단되었습니다."),
    TTS_CALL_FAILED("tts", 502, "음성 합성 서비스 호출에 실패했습니다."),
    TTS_REQUEST_SERIALIZE_FAILED("tts", 500, "음성 합성 요청 본문 생성에 실패했습니다."),

    // api proxy
    API_WEBHOOK_REQUEST_FAILED("api", 502, "n8n 웹훅 요청에 실패했습니다."),
    API_WEBHOOK_REQUEST_INTERRUPTED("api", 502, "n8n 웹훅 요청이 중단되었습니다."),
    API_WEBHOOK_INVALID_URL("api", 500, "n8n 웹훅 URL 설정이 올바르지 않습니다."),
    API_WEBHOOK_CALL_FAILED("api", 502, "n8n 웹훅 호출에 실패했습니다."),

    // common
    LOG_WRITE_FAILED("logging", 500, "상호작용 로그 기록에 실패했습니다."),
    COMMON_INTERNAL_SERVER_ERROR("server", 500, "서버 내부 오류가 발생했습니다.");

    private final String stage;
    private final int status;
    private final String message;

    ErrorCode(String stage, int status, String message) {
        this.stage = stage;
        this.status = status;
        this.message = message;
    }

    public String stage() {
        return stage;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }

    static {
        for (ErrorCode code : values()) {
            if (!hasValidPrefix(code.name())) {
                throw new IllegalStateException("ErrorCode 접두어 규칙 위반: " + code.name());
            }
        }
    }

    private static boolean hasValidPrefix(String name) {
        return name.startsWith("VOICE_")
                || name.startsWith("WHISPER_")
                || name.startsWith("N8N_")
                || name.startsWith("TTS_")
                || name.startsWith("API_")
                || name.startsWith("LOG_")
                || name.startsWith("COMMON_");
    }
}
