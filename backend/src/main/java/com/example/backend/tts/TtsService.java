package com.example.backend.tts;

import com.example.backend.common.exception.AppException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.tts.TtsClient;
import com.example.backend.tts.TtsProperties;
import com.example.backend.tts.dto.TtsAudioResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final TtsClient ttsClient;
    private final TtsProperties properties;

    public TtsService(TtsClient ttsClient, TtsProperties properties) {
        this.ttsClient = ttsClient;
        this.properties = properties;
    }

    public TtsAudioResponse synthesize(String rawText) {
        long startedAt = System.currentTimeMillis();
        if (!properties.enabled()) {
            throw new AppException(ErrorCode.TTS_DISABLED);
        }

        String text = rawText == null ? "" : rawText.trim();
        if (text.isBlank()) {
            throw new AppException(ErrorCode.TTS_TEXT_REQUIRED);
        }
        if (text.length() > properties.maxTextChars()) {
            throw new AppException(ErrorCode.TTS_TEXT_TOO_LARGE);
        }

        TtsAudioResponse response = ttsClient.synthesize(text);
        long elapsedMillis = System.currentTimeMillis() - startedAt;
        log.info(
                "TTS synthesized. textLength={}, audioBytes={}, elapsedMillis={}",
                text.length(),
                response.audioBytes().length,
                elapsedMillis
        );
        return response;
    }
}
