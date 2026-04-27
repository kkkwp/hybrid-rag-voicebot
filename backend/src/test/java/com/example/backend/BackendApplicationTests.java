package com.example.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("로컬 통합 환경(Elasticsearch, Ollama) 의존으로 CI/샌드박스에서는 비활성화")
@SpringBootTest
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
