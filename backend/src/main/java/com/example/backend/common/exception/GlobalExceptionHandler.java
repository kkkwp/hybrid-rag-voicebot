package com.example.backend.common.exception;

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

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleAppException(AppException error) {
        ErrorCode code = error.errorCode();
        if (code.status() >= 500) {
            log.warn("애플리케이션 예외 발생. code={} message={}", code.name(), code.message(), error);
        }
        return error(code);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiErrorResponse> handleMultipartException(Exception error) {
        return error(ErrorCode.VOICE_MULTIPART_INVALID);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException error) {
        return error(ErrorCode.VOICE_AUDIO_TOO_LARGE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandledException(Exception error) {
        log.error("처리되지 않은 서버 예외가 발생했습니다.", error);
        return error(ErrorCode.COMMON_INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiErrorResponse> error(ErrorCode code) {
        HttpStatus status = HttpStatus.resolve(code.status());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = ErrorCode.COMMON_INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code.name(), code.stage(), status.value(), code.message()));
    }
}
