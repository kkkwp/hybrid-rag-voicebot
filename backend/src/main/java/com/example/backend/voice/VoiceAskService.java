package com.example.backend.voice;

import com.example.backend.interaction.InteractionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 음성 파일을 받아 텍스트로 변환(STT)한 뒤 답변을 생성하는 서비스.
 *
 * 처리 흐름:
 *   1. 업로드된 음성 파일을 검증한다 (형식, 크기).
 *   2. Whisper(OpenAI의 STT 모델)를 통해 음성을 텍스트로 변환한다.
 *   3. 변환된 텍스트를 n8n 웹훅으로 전달해 멀티 에이전트 답변을 받는다.
 *   4. 전체 인터랙션(음성→텍스트→답변)을 로그로 기록한다.
 */
@Service
public class VoiceAskService {

    private static final Logger log = LoggerFactory.getLogger(VoiceAskService.class);
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".webm", ".wav", ".mp3", ".m4a", ".ogg", ".aac", ".flac"
    );

    private final WhisperClient whisperClient;
    private final N8nWebhookClient n8nWebhookClient;
    private final VoiceAskProperties properties;
    private final InteractionLogService interactionLogService;

    public VoiceAskService(
            WhisperClient whisperClient,
            N8nWebhookClient n8nWebhookClient,
            VoiceAskProperties properties,
            InteractionLogService interactionLogService
    ) {
        this.whisperClient = whisperClient;
        this.n8nWebhookClient = n8nWebhookClient;
        this.properties = properties;
        this.interactionLogService = interactionLogService;
    }

    public VoiceAskResponse ask(MultipartFile file, List<String> agents, Integer size) {
        long startedAt = System.currentTimeMillis();
        validate(file);

        TranscriptionResult rawTranscription = transcribe(file);
        String text = normalizeTranscription(rawTranscription.text());
        if (text.isBlank()) {
            throw new VoiceAskException("음성 인식 결과가 비었습니다", 400);
        }
        TranscriptionResult transcription = new TranscriptionResult(
                text,
                rawTranscription.language(),
                rawTranscription.durationSeconds(),
                rawTranscription.segments()
        );

        log.info(
                "Voice ask transcribed. textLength={}, language={}, durationSeconds={}, agents={}",
                text.length(),
                transcription.language(),
                transcription.durationSeconds(),
                agents
        );
        Map<String, Object> answer = n8nWebhookClient.ask(text, agents, size);
        long elapsedMillis = System.currentTimeMillis() - startedAt;
        interactionLogService.logVoice(transcription, answer, agents, size, elapsedMillis);
        log.info(
                "Voice ask finished. textLength={}, language={}, durationSeconds={}, agents={}, elapsedMillis={}",
                text.length(),
                transcription.language(),
                transcription.durationSeconds(),
                agents,
                elapsedMillis
        );
        return new VoiceAskResponse(transcription, answer);
    }

    private String normalizeTranscription(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }

        String normalized = source
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .replaceAll("([,.!?])\\1+", "$1")
                .replaceAll("^[\\p{P}\\s]+", "")
                .replaceAll("[\\p{P}\\s]+$", "");

        if (!normalized.matches(".*[\\p{L}\\p{N}].*")) {
            return "";
        }
        return normalized;
    }

    private TranscriptionResult transcribe(MultipartFile file) {
        try {
            return whisperClient.transcribe(file.getBytes(), file.getOriginalFilename());
        } catch (IOException error) {
            throw new VoiceAskException("failed to read audio upload", 400, error);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new VoiceAskException("empty audio", 400);
        }
        if (!isAudioUpload(file)) {
            throw new VoiceAskException("audio content type is required", 400);
        }
        if (file.getSize() > properties.maxAudioBytes()) {
            throw new VoiceAskException("audio payload too large", 413);
        }
    }

    /**
     * Content-Type 헤더 또는 파일 확장자로 오디오 파일 여부를 판단한다.
     * 브라우저마다 Content-Type을 다르게 보낼 수 있어 확장자도 함께 확인한다.
     */
    private boolean isAudioUpload(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return true;
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return false;
        }

        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        return AUDIO_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
    }
}
