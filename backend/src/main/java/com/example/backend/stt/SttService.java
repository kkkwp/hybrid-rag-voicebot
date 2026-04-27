package com.example.backend.stt;

import com.example.backend.common.exception.AppException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.stt.WhisperClient;
import com.example.backend.stt.SttProperties;
import com.example.backend.stt.dto.TranscriptionResult;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Service
public class SttService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".webm", ".wav", ".mp3", ".m4a", ".ogg", ".aac", ".flac"
    );

    private final WhisperClient whisperClient;
    private final SttProperties properties;

    public SttService(WhisperClient whisperClient, SttProperties properties) {
        this.whisperClient = whisperClient;
        this.properties = properties;
    }

    public TranscriptionResult transcribe(MultipartFile file) {
        validate(file);

        TranscriptionResult raw = transcribeRaw(file);
        String normalizedText = normalizeTranscription(raw.text());
        if (normalizedText.isBlank()) {
            throw new AppException(ErrorCode.VOICE_TRANSCRIPTION_EMPTY);
        }

        return new TranscriptionResult(
                normalizedText,
                raw.language(),
                raw.durationSeconds(),
                raw.segments()
        );
    }

    private TranscriptionResult transcribeRaw(MultipartFile file) {
        try {
            return whisperClient.transcribe(file.getBytes(), file.getOriginalFilename());
        } catch (IOException error) {
            throw new AppException(ErrorCode.VOICE_AUDIO_READ_FAILED, error);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.VOICE_EMPTY_AUDIO);
        }
        if (!isAudioUpload(file)) {
            throw new AppException(ErrorCode.VOICE_CONTENT_TYPE_REQUIRED);
        }
        if (file.getSize() > properties.maxAudioBytes()) {
            throw new AppException(ErrorCode.VOICE_AUDIO_TOO_LARGE);
        }
    }

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
}
