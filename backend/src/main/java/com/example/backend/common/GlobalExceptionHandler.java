package com.example.backend.common;

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
    public ResponseEntity<ErrorResponse> handleVoiceAskException(VoiceAskException e) {
        return error(e.errorCode());
    }

    @ExceptionHandler(WhisperException.class)
    public ResponseEntity<ErrorResponse> handleWhisperException(WhisperException e) {
        log.warn("음성 인식 서비스 오류. message={}", e.getMessage(), e);
        return error(e.errorCode());
    }

    @ExceptionHandler(N8nWebhookException.class)
    public ResponseEntity<ErrorResponse> handleN8nWebhookException(N8nWebhookException e) {
        log.warn("워크플로우 서비스 오류. message={}", e.getMessage(), e.getCause());
        return error(e.errorCode());
    }

    @ExceptionHandler(TtsException.class)
    public ResponseEntity<ErrorResponse> handleTtsException(TtsException e) {
        if (e.errorCode().status() >= 500) {
            log.warn("TTS 서비스 오류. message={}", e.getMessage(), e);
        }
        return error(e.errorCode());
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ErrorResponse> handleMultipartException(Exception e) {
        return error(ErrorCode.MISSING_AUDIO_PART);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        return error(ErrorCode.AUDIO_TOO_LARGE);
    }

    private ResponseEntity<ErrorResponse> error(ErrorCode errorCode) {
        HttpStatus status = HttpStatus.resolve(errorCode.status());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(errorCode.stage(), status.value(), errorCode.message()));
    }

    public record ErrorResponse(
            String stage,
            int status,
            String message
    ) {
    }
}
