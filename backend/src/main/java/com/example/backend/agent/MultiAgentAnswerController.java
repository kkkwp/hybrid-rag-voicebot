package com.example.backend.agent;

import com.example.backend.agent.dto.MultiAgentAnswerRequest;
import com.example.backend.agent.dto.MultiAgentAnswerResponse;
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
    public MultiAgentAnswerResponse answer(@RequestBody MultiAgentAnswerRequest request) {
        return multiAgentAnswerService.answer(request);
    }
}
