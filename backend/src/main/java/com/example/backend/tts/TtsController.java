package com.example.backend.tts;

import com.example.backend.tts.dto.TtsAudioResponse;
import com.example.backend.tts.dto.TtsRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest request) {
        TtsAudioResponse response = ttsService.synthesize(request == null ? null : request.text());
        MediaType mediaType = MediaType.parseMediaType(response.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(response.audioBytes().length))
                .body(response.audioBytes());
    }
}
