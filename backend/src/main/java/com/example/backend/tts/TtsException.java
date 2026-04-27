package com.example.backend.tts;

import com.example.backend.common.ErrorCode;

public class TtsException extends RuntimeException {

    private final ErrorCode errorCode;

    public TtsException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public TtsException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
