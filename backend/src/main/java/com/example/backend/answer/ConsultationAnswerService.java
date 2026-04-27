package com.example.backend.answer;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LLM(대형 언어 모델) 호출을 담당하는 서비스.
 *
 * 내부적으로 Ollama를 사용한다. Ollama는 llama3.1 같은 오픈소스 LLM을
 * 로컬 서버에서 실행해주는 런타임이다. Spring AI의 ChatModel 인터페이스를 통해
 * 호출하므로, 나중에 다른 LLM 제공자(OpenAI 등)로 교체해도 이 클래스는 수정하지 않아도 된다.
 */
@Service
public class ConsultationAnswerService {

    private final ChatModel chatModel;

    public ConsultationAnswerService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 여러 도메인 에이전트의 결과를 하나의 최종 답변으로 합치는 집계(aggregator) 모델을 호출한다.
     *
     * 짧고 사실적인 답변이 목적이므로 temperature를 낮게(0.2) 설정한다.
     * temperature가 낮을수록 LLM이 창의적 표현보다 근거에 충실한 답변을 생성한다.
     */
    public String callAggregatorModel(List<Message> messages) {
        return callChatModel(messages, 260, 0.2, 2048);
    }

    /**
     * Ollama 옵션을 구성하고 LLM을 호출한 뒤 생성된 텍스트를 반환한다.
     *
     * @param messages    시스템 메시지(역할·규칙)와 사용자 메시지(질문·컨텍스트)의 목록
     * @param numPredict  생성할 최대 토큰 수 (1토큰 ≈ 한글 1~2자)
     * @param temperature 답변의 무작위성. 0에 가까울수록 결정적, 1에 가까울수록 창의적
     * @param numCtx      모델이 한 번에 처리할 수 있는 컨텍스트 창 크기(토큰 수)
     */
    private String callChatModel(List<Message> messages, int numPredict, double temperature, int numCtx) {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .disableThinking()   // "thinking" 토큰(내부 추론 과정 출력)을 응답에서 제외해 속도 향상
                .numPredict(numPredict)
                .temperature(temperature)
                .numCtx(numCtx)
                .build();

        return chatModel.call(new Prompt(messages, options))
                .getResult()
                .getOutput()
                .getText();
    }
}
