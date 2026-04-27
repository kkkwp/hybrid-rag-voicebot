package com.example.backend.voice;

import com.example.backend.common.ErrorCode;

public class N8nWebhookException extends RuntimeException {

    private final ErrorCode errorCode;

    public N8nWebhookException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public N8nWebhookException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
