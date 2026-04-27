package com.example.backend.ops;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ModelVerificationController {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public ModelVerificationController(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    @GetMapping("/verify/models")
    public Map<String, Object> verify(@RequestParam(defaultValue = "배송이 늦어졌어요.") String text) {
        float[] embedding = embeddingModel.embed(text);
        String chatReply = chatModel.call("다음 문장을 한 줄로 요약해 주세요: " + text);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("input", text);
        response.put("embeddingDimensions", embedding.length);
        response.put("chatReply", chatReply);
        return response;
    }
}
