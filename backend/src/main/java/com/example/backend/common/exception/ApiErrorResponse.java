package com.example.backend.common.exception;

public record ApiErrorResponse(
        String code,
        String stage,
        int status,
        String message
) {
}
