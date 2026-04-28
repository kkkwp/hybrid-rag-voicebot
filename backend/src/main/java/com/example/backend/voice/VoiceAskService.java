package com.example.backend.voice;

import com.example.backend.common.ErrorCode;
import com.example.backend.interaction.InteractionLogService;
import com.example.backend.progress.ProgressEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // 주문번호 후처리 패턴: "dlv minus 천일" → "DLV-1001"
    private static final Pattern ORDER_ID_RAW = Pattern.compile(
            "(?i)(DLV|RFD|디\\s*엘\\s*브이|알\\s*에프\\s*디)" +
            "(?:\\s*-\\s*|\\s+(?:minus|마이너스|빼기|대시)\\s+|\\s+)" +
            "([\\d일이삼사오육칠팔구공영천백십]{1,15})"
    );

    private final WhisperClient whisperClient;
    private final N8nWebhookClient n8nWebhookClient;
    private final VoiceAskProperties properties;
    private final InteractionLogService interactionLogService;
    private final ProgressEventService progressEventService;

    public VoiceAskService(
            WhisperClient whisperClient,
            N8nWebhookClient n8nWebhookClient,
            VoiceAskProperties properties,
            InteractionLogService interactionLogService,
            ProgressEventService progressEventService
    ) {
        this.whisperClient = whisperClient;
        this.n8nWebhookClient = n8nWebhookClient;
        this.properties = properties;
        this.interactionLogService = interactionLogService;
        this.progressEventService = progressEventService;
    }

    public VoiceAskResponse ask(MultipartFile file, List<String> agents, Integer size, String sessionId) {
        long startedAt = System.currentTimeMillis();
        validate(file);

        progressEventService.emit(sessionId, "stt", "음성을 텍스트로 변환 중입니다.");
        TranscriptionResult rawTranscription = transcribe(file);
        String text = normalizeTranscription(rawTranscription.text());
        if (text.isBlank()) {
            progressEventService.error(sessionId, "음성 인식 결과가 비어 있습니다.");
            throw new VoiceAskException(ErrorCode.EMPTY_TRANSCRIPTION);
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
        progressEventService.emit(sessionId, "n8n", "n8n workflow로 전달 중입니다.");
        Map<String, Object> answer = n8nWebhookClient.ask(text, agents, size, sessionId);
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
        progressEventService.complete(sessionId);
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

        normalized = normalizeOrderIds(normalized);

        if (!normalized.matches(".*[\\p{L}\\p{N}].*")) {
            return "";
        }
        return normalized;
    }

    // "dlv minus 천일" / "디엘브이 마이너스 1001" → "DLV-1001"
    private String normalizeOrderIds(String text) {
        Matcher m = ORDER_ID_RAW.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = resolvePrefix(m.group(1));
            int num = parseNumber(m.group(2));
            if (num >= 1 && num <= 9999) {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        prefix + "-" + String.format("%04d", num)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolvePrefix(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return switch (key) {
            case "dlv", "디엘브이" -> "DLV";
            case "rfd", "알에프디" -> "RFD";
            default -> raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        };
    }

    private int parseNumber(String s) {
        if (s == null || s.isBlank()) return 0;
        s = s.trim();
        if (s.matches("\\d+")) {
            int n = Integer.parseInt(s);
            return (n >= 1 && n <= 9999) ? n : 0;
        }
        return parseKoreanNumber(s);
    }

    // 천백십일 체계 파싱: "천이백삼십사" → 1234, "천일" → 1001
    private int parseKoreanNumber(String s) {
        int result = 0;

        int idx = s.indexOf("천");
        if (idx >= 0) {
            String before = s.substring(0, idx);
            result += (before.isEmpty() ? 1 : koreanDigit(before)) * 1000;
            s = s.substring(idx + 1);
        }
        idx = s.indexOf("백");
        if (idx >= 0) {
            String before = s.substring(0, idx);
            result += (before.isEmpty() ? 1 : koreanDigit(before)) * 100;
            s = s.substring(idx + 1);
        }
        idx = s.indexOf("십");
        if (idx >= 0) {
            String before = s.substring(0, idx);
            result += (before.isEmpty() ? 1 : koreanDigit(before)) * 10;
            s = s.substring(idx + 1);
        }
        if (!s.isEmpty()) result += koreanDigit(s);

        return result;
    }

    private int koreanDigit(String s) {
        return switch (s.trim()) {
            case "일", "1" -> 1;
            case "이", "2" -> 2;
            case "삼", "3" -> 3;
            case "사", "4" -> 4;
            case "오", "5" -> 5;
            case "육", "6" -> 6;
            case "칠", "7" -> 7;
            case "팔", "8" -> 8;
            case "구", "9" -> 9;
            case "공", "영", "0" -> 0;
            default -> 0;
        };
    }

    private TranscriptionResult transcribe(MultipartFile file) {
        try {
            return whisperClient.transcribe(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException error) {
            throw new VoiceAskException(ErrorCode.AUDIO_READ_FAILED, error);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new VoiceAskException(ErrorCode.EMPTY_AUDIO);
        }
        if (!isAudioUpload(file)) {
            throw new VoiceAskException(ErrorCode.INVALID_AUDIO_FORMAT);
        }
        if (file.getSize() > properties.maxAudioBytes()) {
            throw new VoiceAskException(ErrorCode.AUDIO_TOO_LARGE);
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
