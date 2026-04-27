package com.example.backend.prompt;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM에게 전달할 프롬프트(지시문)를 조립하는 컴포넌트.
 *
 * LLM과 대화할 때는 두 종류의 메시지를 함께 보낸다.
 *   - SystemMessage: LLM의 역할과 규칙을 정의하는 지시문. 사용자에게는 보이지 않는다.
 *   - UserMessage:   실제 질문과 검색 결과 등 LLM이 답변에 활용할 데이터.
 *
 * 프롬프트 설계가 LLM 응답 품질에 직접적인 영향을 미치므로,
 * 규칙 변경이 필요할 때는 이 클래스의 문자열 상수만 수정하면 된다.
 */
@Component
public class ConsultationPromptTemplate {

    /**
     * 모든 LLM 호출에 공통으로 적용되는 grounding 규칙.
     * "grounding"이란 LLM이 검색 결과 등 실제 근거에 기반해서만 답하도록 제한하는 기법으로,
     * LLM이 사실이 아닌 내용을 그럴듯하게 만들어내는 "hallucination(환각)"을 방지한다.
     */
    private static final String COMMON_GROUNDING_RULES = """
            공통 규칙:
            - 제공된 검색 근거 안에서만 답하세요.
            - 근거에 없는 정책, 조회 채널, 처리 가능 여부, 도착일, 처리 기간을 만들지 마세요.
            - 근거가 부족하면 확정하지 말고 확인이 필요하다고 말하세요.
            - 사용자 질문이 공백/구두점만 있거나 의미 있는 단어·주문번호가 없으면, 임의 추정 없이 "입력된 질문으로는 조회할 수 없어 질문을 다시 입력해 달라"는 취지로 답하세요.
            - 의미 없는 질문에서는 특정 주문번호나 상태를 추정해 만들지 마세요.
            - 고객에게 말하듯이 한국어로 간결하고 정중하게 답하세요.
            - 고객의 질문 문장을 첫 문장으로 그대로 반복하지 마세요.
            """;

    /**
     * 여러 도메인 에이전트의 결과를 최종 답변으로 합치는 aggregator LLM 호출에 사용할 메시지 목록을 반환한다.
     *
     * @param question           원래 고객 질문
     * @param agentResultContext 각 도메인 에이전트(배송, 환불/교환)가 생성한 결과를 이어 붙인 텍스트
     */
    public List<Message> aggregatorMessages(String question, String agentResultContext) {
        String system = """
                당신은 고객센터 메인 상담 에이전트입니다.
                원래 사용자 질문과 도메인 에이전트들의 답변을 바탕으로 최종 답변을 작성하세요.

                %s
                Aggregator 규칙:
                - 도메인 에이전트의 `판단`, `근거`, `다음행동`만 사용하세요.
                - 서로 충돌하는 내용은 확정적으로 말하지 말고 확인이 필요하다고 말하세요.
                - 전체 답변은 정확히 3문장으로 작성하세요.
                - 줄바꿈 없이 하나의 문단으로 작성하세요.
                - 답변 앞에 원래 사용자 질문, 제목, 요약 라벨, 빈 줄을 붙이지 마세요.
                - 첫 문장은 고객 질문을 반복하지 말고, 공감과 확인 의사를 함께 표현하세요.
                - 마지막 문장은 이미 확인된 내용을 다시 확인하겠다고 하지 말고, 고객이 추가로 확인하고 싶은 부분을 도와주겠다는 톤으로 마무리하세요.
                - 단, 도메인 에이전트의 `다음행동`에 구체적인 후속 조치가 있으면 그 의미를 자연스럽게 포함하세요.
                - 번호 목록을 만들지 마세요.
                - 도메인 에이전트가 `확인 필요`라고 판단한 내용을 가능하다고 바꾸지 마세요.
                - `내부 정책`, `내부`, `유사 상담 기록`, `도메인 에이전트`, `판단`, `근거`, `다음행동` 같은 내부 용어를 고객 답변에 쓰지 마세요.
                - 근거에 없는 홈페이지, 고객센터, 앱, 1:1 문의, 전화 같은 채널을 만들지 마세요.
                - 질문이 의미 없는 입력(예: ",,,,", "...", 공백)으로 판단되면 반드시 조회 불가를 알리고 재입력을 요청하세요.
                """.formatted(COMMON_GROUNDING_RULES);
        String user = """
                원래 사용자 질문:
                %s

                도메인 에이전트 결과:
                %s
                """.formatted(question, agentResultContext);
        return List.of(new SystemMessage(system), new UserMessage(user));
    }
}
