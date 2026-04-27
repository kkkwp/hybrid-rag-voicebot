package com.example.backend.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCodeContractTest {

    @Test
    void errorCode_name_prefix_should_follow_convention() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(
                    code.name().matches("^(VOICE|WHISPER|N8N|TTS|API|LOG|COMMON)_.*$"),
                    () -> "접두어 규칙 위반: " + code.name()
            );
        }
    }

    @Test
    void errorCode_message_should_be_korean() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(
                    code.message().matches(".*[가-힣].*"),
                    () -> "한글 메시지 아님: " + code.name()
            );
            assertFalse(code.message().isBlank(), () -> "빈 메시지: " + code.name());
        }
    }
}
