package com.example.backend.voice;

import com.example.backend.common.ErrorCode;

public class VoiceAskException extends RuntimeException {

    private final ErrorCode errorCode;

    public VoiceAskException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public VoiceAskException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
