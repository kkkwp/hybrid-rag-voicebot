package com.example.backend.exception;

public record ApiErrorResponse(
        String stage,
        int status,
        String message
) {
}
