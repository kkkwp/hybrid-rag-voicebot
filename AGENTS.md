# AGENTS.md

이 파일은 세션이 초기화되어도 Codex/AI 작업자가 프로젝트 전제를 잃지 않도록 남기는 작업 메모다.

## 현재 프로젝트 목표

- 상담 통화 로그를 Elasticsearch에 적재한다.
- Spring AI로 embedding model과 LLM model을 관리한다.
- Spring Boot가 Elasticsearch 인덱스 스키마와 embedding 보강을 책임진다.
- 검색은 lexical + vector hybrid retrieval을 사용한다.
- 최종 답변은 LLM이 생성한다.
- n8n은 멀티 에이전트 orchestration과 시연 흐름을 담당한다.
- 외부 시연은 Spring Boot 정적 웹페이지에서 텍스트 입력 또는 음성 입력을 선택해 n8n test webhook을 호출하는 방식으로 한다.

## 중요 운영 결정

- n8n workflow는 `Publish`하지 않는다.
- 실시간으로 노드별 실행 과정과 결과를 보기 위해 항상 `Execute workflow` + test webhook을 사용한다.
- host `bootRun`에서 `POST /demo/ask`는 n8n test webhook인 `http://localhost:5678/webhook-test/consultation-multi-agent`로 전달한다.
- Docker backend에서 `POST /demo/ask`는 compose 내부 n8n 주소인 `http://n8n:5678/webhook-test/consultation-multi-agent`로 전달한다.
- `/demo/ask`는 브라우저에서 받은 JSON body를 재조립하지 않고 그대로 n8n test webhook으로 pass-through 한다.
- `/demo/ask`의 Java `HttpClient`는 반드시 `HttpClient.Version.HTTP_1_1`로 고정한다. 기본 HTTP/2 시도 때문에 n8n webhook 응답이 hang 되는 문제가 있었다.
- n8n v2 계열에서 `Publish`는 예전 `Active`에 해당하지만, 현재 MVP 시연에서는 사용하지 않는다.
- 비개발자 시연에서도 `http://localhost:8080/` 웹 화면으로 질문을 넣되, n8n 화면은 `Execute workflow` 상태로 열어두고 실시간 실행 과정을 함께 보여준다.
- React UI는 후순위다. 현재는 정적 HTML + Spring Boot proxy로 충분하다.
- Thymeleaf는 필수 아님. 현재 화면은 서버 렌더링이 아니라 정적 HTML + fetch API 구조다.
- STT UI는 텍스트 입력을 대체하지 않는다. 한 화면에 텍스트 입력과 음성 입력 두 가지 선택지를 모두 제공한다.

## 주요 파일 구조

- `backend/`: Spring Boot 4 / Java 21 애플리케이션
- `backend/src/main/java/com/example/backend/BackendApplication.java`: Spring Boot entry point
- `backend/src/main/java/com/example/backend/domain/Consultation.java`: 상담 문서 도메인
- `backend/src/main/java/com/example/backend/infra/IndexInitializer.java`: Elasticsearch 인덱스 스키마 생성. `delivery-orders-v1`, `refund-exchange-orders-v1`, `support-manuals-v1` 생성
- `backend/src/main/java/com/example/backend/infra/EmbeddingBackfillService.java`: raw 문서 embedding 보강. 현재 `delivery-orders-v1`, `refund-exchange-orders-v1`, `support-manuals-v1` 순회
- `backend/src/main/java/com/example/backend/search/HybridSearchService.java`: lexical + vector 검색과 앱 단 RRF
- `backend/src/main/java/com/example/backend/answer/ConsultationAnswerService.java`: RAG 답변 생성
- `backend/src/main/java/com/example/backend/agent/MultiAgentAnswerService.java`: 도메인 agent 코드 기반 구조화 요약 + LLM Aggregator
- `backend/src/main/java/com/example/backend/prompt/ConsultationPromptTemplate.java`: RAG/domain agent/Aggregator 공통 프롬프트 템플릿, Spring AI `SystemMessage` / `UserMessage` 생성
- `backend/src/main/java/com/example/backend/demo/DemoAskController.java`: 웹 시연 페이지에서 n8n test webhook으로 프록시
- `backend/src/main/java/com/example/backend/voice/VoiceAskProperties.java`: STT/n8n voice ask 설정
- `backend/src/main/java/com/example/backend/voice/WhisperClient.java`: Spring Boot에서 FastAPI Whisper `/transcribe` 호출
- `backend/src/main/java/com/example/backend/voice/TranscriptionResult.java`: Whisper 전사 응답 record
- `backend/src/main/java/com/example/backend/voice/N8nWebhookClient.java`: 전사 text를 n8n test webhook으로 전달
- `backend/src/main/java/com/example/backend/voice/VoiceAskService.java`: 오디오 검증, Whisper 전사, n8n 호출 오케스트레이션
- `backend/src/main/java/com/example/backend/voice/VoiceAskController.java`: `POST /voice/ask` multipart REST endpoint
- `backend/src/main/java/com/example/backend/voice/VoiceAskResponse.java`: `/voice/ask` 응답용 전사 결과 + n8n 응답 wrapper
- `backend/src/main/resources/static/index.html`: 시연용 정적 웹페이지
- `whisper/`: FastAPI + faster-whisper + ffmpeg 기반 STT 사이드카
- `n8n/workflows/consultation-multi-agent-mvp.json`: n8n workflow export
- `log/text-interactions.jsonl`: 텍스트 질문/응답 runtime 로그
- `log/voice-interactions.jsonl`: 음성 질문/응답 runtime 로그
- `data-seed/support-manuals.jsonl`: 상담 매뉴얼 seed 50건. 현재 Logstash 자동 적재 대상이 아님
- `data-seed/delivery-orders.jsonl`: 배송 상태 seed 50건
- `logstash/pipeline/`: Logstash 적재 파이프라인
- `README.md`: 프로젝트 설명

## 데이터 적재 흐름

1. 사용자가 `data-seed/*.jsonl`에 JSON Lines 형식 seed 데이터를 둔다.
2. Logstash가 `/data-seed/*.jsonl`을 감지한다.
3. Logstash는 seed 문서를 도메인별 Elasticsearch index에 upsert한다.
4. Spring Boot가 raw 문서 중 embedding이 없는 문서를 찾아 embedding model로 벡터를 생성한다.
5. 생성된 벡터는 같은 Elasticsearch 문서의 `embedding` 필드에 저장된다.

Logstash는 Ollama나 Spring AI를 직접 호출하지 않는다.

데이터 확장 MVP는 `delivery-orders-v1`, `refund-exchange-orders-v1`, `support-manuals-v1` 도메인 인덱스로 분리한다.
Logstash는 `data-seed/*.jsonl` seed 파일을 파일별로 도메인 인덱스에 적재하도록 분기한다.
`IndexInitializer`는 `delivery-orders-v1`, `refund-exchange-orders-v1`, `support-manuals-v1` 인덱스를 생성한다.
새 도메인 인덱스들은 모두 `content` Korean analyzer와 `embedding` 1024차원 dense vector mapping을 가진다.
Logstash index routing:

- `/data-seed/delivery-orders.jsonl` → `delivery-orders-v1`, document id `%{orderId}`
- `/data-seed/refund-exchange-orders.jsonl` → `refund-exchange-orders-v1`, document id `%{orderId}`
- `/data-seed/support-manuals.jsonl` → `support-manuals-v1`, document id `%{manualId}`

## 상담 문서 기본 필드

- `callId`: 통화 식별자
- `turn`: 대화 턴 번호
- `speaker`: `customer` 또는 `agent`
- `name`: 발화자 이름
- `content`: 발화 내용
- `occurredAt`: 발화 시각
- `embedding`: Spring AI가 채우는 dense vector

## 도메인 검증 데이터 전제

- 배송 검증 데이터는 `data-seed/delivery-orders.jsonl`에 50건 생성한다.
- 환불/교환 검증 데이터는 `data-seed/refund-exchange-orders.jsonl`에 50건 생성한다.
- 상담 매뉴얼 데이터는 `data-seed/support-manuals.jsonl`에 50건 생성되어 있다.
- seed 파일은 JSON Lines 형식이며 한 줄이 Elasticsearch 문서 1개다.
- 배송 주문번호는 `DLV-1001`부터 `DLV-1050`까지 사용한다.
- 환불/교환 주문번호는 `RFD-2001`부터 `RFD-2050`까지 사용한다.
- 검색이 주문번호를 정확히 잡을 수 있도록 `content`에는 `주문번호`, 실제 주문번호, 도메인 키워드, 상태, 위치/예정일을 명시한다.

## Hybrid Retrieval 구현 전제

- Elasticsearch Basic 라이선스에서는 ES RRF retriever 사용이 제한될 수 있어 앱 단 RRF를 사용한다.
- lexical 검색은 `content` 기반 full-text 검색이다.
- vector 검색은 query embedding 기반 kNN 검색이다.
- fusion은 Spring Boot `HybridSearchService`에서 수행한다.
- RRF 기본값:
  - `rankConstant = 60`
  - lexical weight `1.0`
  - vector weight `1.0`
- 중복 문서는 `callId-turn` 기준으로 dedup한다.

## 모델 전제

- LLM model: `llama3.1:8b`
- Embedding model: `bge-m3:latest`
- STT model: `faster-whisper large`
- 기본 시연 실행에서는 Ollama를 host에서 `localhost:11434`로 실행한다.
- Docker 컨테이너에서 host Ollama를 부를 때는 `host.docker.internal:11434`를 사용한다.

## 멀티 에이전트 구조

- n8n은 Router 역할을 한다.
- Router는 먼저 코드 기반 keyword rule로 빠르게 분기한다.
- keyword로 판단이 어려우면 LLM fallback router를 호출한다.
- 실제 도메인 agent 병렬 실행은 Spring Boot에서 한다.
- Spring Boot는 Java 21 virtual threads로 선택된 agent들의 retrieval/summary를 병렬 실행한다.
- Domain agent는 LLM을 호출하지 않는다.
- Domain agent는 hybrid retrieval 결과를 코드 기반으로 `판단:` / `근거:` / `다음행동:` 3줄 구조화 단답으로 변환한다.
- Aggregator는 단일 agent 선택 시에도 항상 실행한다.
- Aggregator만 LLM을 호출해 구조화된 agent 결과를 최종 고객용 3문장 답변으로 변환한다.
- `metadata.aggregatorSkipped`는 하위 호환 때문에 남아 있지만 항상 `false`로 내려간다.
- 현재 agent:
  - `delivery`
  - `refundExchange`

## 프롬프트 구조

- `ConsultationPromptTemplate`가 프롬프트 책임을 가진다.
- 현재는 문자열 하나로 prompt를 만들지 않고 Spring AI `List<Message>`를 사용한다.
- RAG 답변:
  - `ragAnswerMessages`
  - `SystemMessage`: 상담 지원 AI 역할, 공통 grounding 규칙, 답변 형식
  - `UserMessage`: 사용자 질문, 참고 상담 기록
- Domain agent:
  - `domainAgentMessages`
  - 현재 런타임에서는 호출하지 않는다.
  - domain agent LLM 호출은 latency 문제로 제거했다.
  - 검색은 원래 사용자 질문으로 수행하고, 결과는 코드 기반 deterministic summary로 만든다.
- Aggregator:
  - `aggregatorMessages`
  - `SystemMessage`: 메인 상담 에이전트 역할, 공통 grounding 규칙, 3문장 최종 답변 규칙
  - `UserMessage`: 원래 질문, 도메인 에이전트 결과
- 공통 grounding 규칙에는 근거 없는 정책/채널/가능 여부/기간 생성 금지와 고객 질문 첫 문장 반복 금지가 포함된다.

## n8n Workflow 사용 규칙

- 시연 전 `n8n/workflows/consultation-multi-agent-mvp.json`를 import한다.
- workflow는 `Publish`하지 않는다.
- production webhook URL:
  - `http://localhost:5678/webhook/consultation-multi-agent`
- test webhook URL:
  - `http://localhost:5678/webhook-test/consultation-multi-agent`
- 현재 웹 시연에서는 test webhook만 사용한다.
- n8n UI에서 실시간 결과를 보려면 workflow 화면에서 `Execute workflow`를 먼저 누른 뒤 웹페이지에서 질문을 전송한다.
- production webhook과 `Executions` 탭은 운영형 확인 방식이며, 현재 1회성 시연 목적에는 우선 사용하지 않는다.

## 웹 시연 흐름

1. host에서 Ollama를 실행한다.
2. `ollama pull bge-m3`, `ollama pull llama3.1:8b`, `curl http://localhost:11434/api/tags`로 모델과 API 응답을 확인한다.
3. `docker compose up -d`로 Elasticsearch, Kibana, Logstash, n8n, whisper, backend를 실행한다.
4. n8n workflow 화면에서 `Execute workflow`를 누른다.
5. 브라우저에서 `http://localhost:8080/` 접속한다.
6. 텍스트 질문 또는 음성 녹음 중 하나를 선택해 전송한다.

텍스트 입력은 `/demo/ask`를 호출하고, Spring Boot는 n8n test webhook으로 프록시한다.
음성 입력은 `/voice/ask`를 호출하고, Spring Boot가 Whisper 전사 후 n8n test webhook으로 프록시한다.

주의:

- Spring Boot backend를 재시작하지 않으면 `/demo/ask`의 HTTP/1.1 고정과 raw body pass-through 변경이 반영되지 않는다.
- 웹에서 응답이 멈춘 것처럼 보이면 backend 로그에서 `Demo ask received...`와 `Multi-agent answer started...`를 확인한다.
- `Demo ask received...`만 있고 `Multi-agent answer started...`가 없으면 n8n workflow가 Spring API 노드까지 도달하지 못한 것이다.

## STT 전환 메모

- compose 서비스명은 `whisper`다.
- STT 사이드카 소스 디렉터리는 `whisper/`다.
- `whisper` 서비스는 `8100:8100`으로 노출한다.
- 모델 캐시는 Docker volume `whisper-models`에 둔다.
- 현재 health endpoint는 `GET http://localhost:8100/healthz`이고 정상 응답은 `{"status":"ok","model":"large"}`다.
- STT 사이드카는 `POST /transcribe`에서 multipart `file`을 받아 ffmpeg로 mono 16k WAV 변환 후 faster-whisper로 전사한다.
- Spring Boot는 `WhisperClient`에서 `java.net.http.HttpClient` HTTP/1.1과 multipart `file` body로 `whisper` 사이드카를 호출한다.
- `/voice/ask` 최종 구조는 Whisper 전사 후 `MultiAgentAnswerService`를 직접 호출하지 않고 n8n test webhook으로 전사 text를 전달하는 방식이다.
- `N8nWebhookClient`도 `HttpClient.Version.HTTP_1_1`을 사용한다.
- `VoiceAskService`는 전사 원문을 로그에 남기지 않고 `textLength`, `language`, `durationSeconds`, `agents`, `elapsedMillis`만 기록한다.
- `/voice/ask`는 multipart `audio` 필수, query/form parameter `agents`, `size` 옵션을 받는다.
- `/voice/ask`는 `VoiceAskService`를 호출하고, `VoiceAskException`/`WhisperException`/`N8nWebhookException`을 400/413/502 계열 JSON으로 매핑한다.
- Spring Boot 4에서 missing multipart part 예외 import는 `org.springframework.web.multipart.support.MissingServletRequestPartException`이다.

## 실행 참고

Spring Boot 실행은 보통 `backend/`에서 수행한다.

```bash
set -a; . ../.env; set +a; GRADLE_USER_HOME=/tmp/gradle-home sh gradlew bootRun
```

컴파일 확인:

```bash
GRADLE_USER_HOME=/tmp/gradle-home sh gradlew compileJava
```

샌드박스에서 Gradle file lock 관련 네트워크 오류가 나면 escalated 실행이 필요할 수 있다.

## 응답 속도 관련 메모

- 로컬 `llama3.1:8b`는 CPU/메모리 상황에 따라 느릴 수 있다.
- 병렬 처리는 retrieval/summary 대기 시간을 겹치게 할 뿐, 단일 LLM 호출 자체를 빠르게 만들지는 않는다.
- Domain agent LLM 호출은 제거했고, 현재 n8n 멀티 에이전트 경로의 LLM 호출은 Aggregator 1회가 핵심이다.
- 프롬프트는 짧고 구체적으로 유지해야 한다.
- `numPredict`, `numCtx`, `evidence size`가 latency에 직접 영향을 준다.

## 문서 업데이트 규칙

- 계획 변경은 `docs/plan.md`에 반영한다.
- 작업 완료/검증 수치는 `docs/task.md`에 반영한다.
- 프로젝트 전체 설명은 `README.md`에 반영한다.
