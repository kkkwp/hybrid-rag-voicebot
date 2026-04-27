package com.example.backend.agent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MultiAgentAnswerController {

    private final MultiAgentAnswerService multiAgentAnswerService;

    public MultiAgentAnswerController(MultiAgentAnswerService multiAgentAnswerService) {
        this.multiAgentAnswerService = multiAgentAnswerService;
    }

    @PostMapping("/agents/multi-answer")
    public MultiAgentAnswerService.MultiAgentAnswerResponse answer(
            @RequestBody MultiAgentAnswerService.MultiAgentAnswerRequest request
    ) {
        return multiAgentAnswerService.answer(request);
    }
}
