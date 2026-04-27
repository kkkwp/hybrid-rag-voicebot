package com.example.backend.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TtsController {

    private static final Logger log = LoggerFactory.getLogger(TtsController.class);

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest request) {
        TtsClient.TtsAudioResponse response = ttsService.synthesize(request == null ? null : request.text());
        MediaType mediaType = MediaType.parseMediaType(response.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(response.audioBytes().length))
                .body(response.audioBytes());
    }

    @ExceptionHandler(TtsException.class)
    public ResponseEntity<TtsErrorResponse> handleTtsException(TtsException error) {
        if (error.statusCode() >= 500 || error.statusCode() < 0) {
            log.warn("TTS failed. statusCode={} message={}", error.statusCode(), error.getMessage(), error);
        }
        int statusCode = error.statusCode() > 0 ? error.statusCode() : HttpStatus.BAD_GATEWAY.value();
        return error(statusCode, "tts", error.getMessage());
    }

    private ResponseEntity<TtsErrorResponse> error(int statusCode, String stage, String message) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new TtsErrorResponse(stage, status.value(), message));
    }

    public record TtsErrorResponse(
            String stage,
            int status,
            String message
    ) {
    }
}
