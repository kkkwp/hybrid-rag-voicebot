package com.example.backend.conversation;

import com.example.backend.common.log.InteractionLogService;
import com.example.backend.conversation.N8nWebhookClient;
import com.example.backend.conversation.dto.ConversationAskResponse;
import com.example.backend.stt.dto.TranscriptionResult;
import com.example.backend.stt.SttService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
public class ConversationAskService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAskService.class);

    private final SttService sttService;
    private final N8nWebhookClient n8nWebhookClient;
    private final InteractionLogService interactionLogService;

    public ConversationAskService(
            SttService sttService,
            N8nWebhookClient n8nWebhookClient,
            InteractionLogService interactionLogService
    ) {
        this.sttService = sttService;
        this.n8nWebhookClient = n8nWebhookClient;
        this.interactionLogService = interactionLogService;
    }

    public ConversationAskResponse ask(MultipartFile file, List<String> agents, Integer size) {
        long startedAt = System.currentTimeMillis();
        TranscriptionResult transcription = sttService.transcribe(file);

        log.info(
                "Conversation ask transcribed. textLength={}, language={}, durationSeconds={}, agents={}",
                transcription.text().length(),
                transcription.language(),
                transcription.durationSeconds(),
                agents
        );

        Map<String, Object> answer = n8nWebhookClient.ask(transcription.text(), agents, size);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        interactionLogService.logVoice(transcription, answer, agents, size, elapsedMillis);
        log.info(
                "Conversation ask finished. textLength={}, language={}, durationSeconds={}, agents={}, elapsedMillis={}",
                transcription.text().length(),
                transcription.language(),
                transcription.durationSeconds(),
                agents,
                elapsedMillis
        );

        return new ConversationAskResponse(transcription, answer);
    }
}
