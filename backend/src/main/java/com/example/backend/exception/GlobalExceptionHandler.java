package com.example.backend.exception;

import com.example.backend.interaction.InteractionLogException;
import com.example.backend.tts.TtsException;
import com.example.backend.voice.N8nWebhookException;
import com.example.backend.voice.VoiceAskException;
import com.example.backend.voice.WhisperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VoiceAskException.class)
    public ResponseEntity<ApiErrorResponse> handleVoiceAskException(VoiceAskException error) {
        return error(error.statusCode(), "voice", error.getMessage());
    }

    @ExceptionHandler(WhisperException.class)
    public ResponseEntity<ApiErrorResponse> handleWhisperException(WhisperException error) {
        log.warn("Voice ask failed at whisper stage. statusCode={} message={}", error.statusCode(), error.getMessage(), error);
        return error(HttpStatus.BAD_GATEWAY.value(), "whisper", error.getMessage());
    }

    @ExceptionHandler(N8nWebhookException.class)
    public ResponseEntity<ApiErrorResponse> handleN8nWebhookException(N8nWebhookException error) {
        log.warn("Voice ask failed at n8n stage. statusCode={} message={}", error.statusCode(), error.getMessage(), error);
        return error(HttpStatus.BAD_GATEWAY.value(), "n8n", error.getMessage());
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiErrorResponse> handleMultipartException(Exception error) {
        return error(HttpStatus.BAD_REQUEST.value(), "voice", error.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException error) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "voice", "audio payload too large");
    }

    @ExceptionHandler(TtsException.class)
    public ResponseEntity<ApiErrorResponse> handleTtsException(TtsException error) {
        if (error.statusCode() >= 500 || error.statusCode() < 0) {
            log.warn("TTS failed. statusCode={} message={}", error.statusCode(), error.getMessage(), error);
        }
        int statusCode = error.statusCode() > 0 ? error.statusCode() : HttpStatus.BAD_GATEWAY.value();
        return error(statusCode, "tts", error.getMessage());
    }

    @ExceptionHandler(InteractionLogException.class)
    public ResponseEntity<ApiErrorResponse> handleInteractionLogException(InteractionLogException error) {
        log.error("Failed to write interaction log", error);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "logging", "failed to write interaction log");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandledException(Exception error) {
        log.error("Unhandled server exception", error);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "server", "internal server error");
    }

    private ResponseEntity<ApiErrorResponse> error(int statusCode, String stage, String message) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(stage, status.value(), message));
    }
}
