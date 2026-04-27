package com.example.backend.voice;

import com.example.backend.common.ErrorCode;

public class WhisperException extends RuntimeException {

    private final ErrorCode errorCode;

    public WhisperException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public WhisperException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
