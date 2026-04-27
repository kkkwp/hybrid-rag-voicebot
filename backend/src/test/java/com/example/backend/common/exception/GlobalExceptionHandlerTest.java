package com.example.backend.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void appException_contract_is_consistent() {
        ResponseEntity<ApiErrorResponse> response = handler.handleAppException(
                new AppException(ErrorCode.API_WEBHOOK_CALL_FAILED)
        );

        ApiErrorResponse body = response.getBody();
        assertEquals(502, response.getStatusCode().value());
        assertEquals("API_WEBHOOK_CALL_FAILED", body.code());
        assertEquals("api", body.stage());
        assertEquals(502, body.status());
        assertEquals("n8n 웹훅 호출에 실패했습니다.", body.message());
        assertTrue(containsKorean(body.message()));
    }

    @Test
    void multipartException_maps_to_voice_multipart_invalid() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMultipartException(new MultipartException("invalid multipart"));

        ApiErrorResponse body = response.getBody();
        assertEquals("VOICE_MULTIPART_INVALID", body.code());
        assertEquals("voice", body.stage());
        assertEquals(400, body.status());
        assertTrue(containsKorean(body.message()));
    }

    @Test
    void maxUploadException_maps_to_voice_audio_too_large() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(1024));

        ApiErrorResponse body = response.getBody();
        assertEquals("VOICE_AUDIO_TOO_LARGE", body.code());
        assertEquals("voice", body.stage());
        assertEquals(413, body.status());
        assertTrue(containsKorean(body.message()));
    }

    @Test
    void unhandledException_maps_to_common_internal_server_error() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnhandledException(new RuntimeException("boom"));

        ApiErrorResponse body = response.getBody();
        assertEquals("COMMON_INTERNAL_SERVER_ERROR", body.code());
        assertEquals("server", body.stage());
        assertEquals(500, body.status());
        assertTrue(containsKorean(body.message()));
    }

    private boolean containsKorean(String value) {
        return value != null && value.matches(".*[가-힣].*");
    }
}
