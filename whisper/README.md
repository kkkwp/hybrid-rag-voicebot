# STT Sidecar

FastAPI + faster-whisper 기반 로컬 STT 사이드카다. Spring Boot는 오디오를 이 서비스의 `/transcribe`로 전달하고, 이 서비스는 ffmpeg로 16kHz mono WAV 변환 후 Whisper 전사를 수행한다.

## Environment

```text
WHISPER_MODEL=tiny
WHISPER_DEVICE=cpu
WHISPER_COMPUTE_TYPE=int8
WHISPER_LANGUAGE=ko
FFMPEG_TIMEOUT_SECONDS=10
```

Model guidance:

- `tiny`: 가장 빠름, 한국어 정확도는 낮을 수 있음
- `base` / `small`: MVP 품질 개선 후보
- `medium` / `large-v3`: 더 정확하지만 CPU에서는 느림

GPU 사용 시 예시:

```text
WHISPER_DEVICE=cuda
WHISPER_COMPUTE_TYPE=float16
```

GPU 전환은 Docker runtime과 CUDA 라이브러리 구성이 필요하므로 현재 MVP 기본값은 CPU다.

## API

### `GET /healthz`

```json
{
  "status": "ok",
  "model": "tiny"
}
```

### `POST /transcribe`

Multipart field:

```text
file=<audio file>
```

Response:

```json
{
  "text": "배송이 늦어요",
  "language": "ko",
  "duration": 3.2,
  "segments": [
    {
      "start": 0.0,
      "end": 3.2,
      "text": "배송이 늦어요"
    }
  ]
}
```

## Local Docker

```bash
docker build -t whisper ./whisper
docker run --rm -p 8100:8100 \
  -e WHISPER_MODEL=tiny \
  -e WHISPER_DEVICE=cpu \
  -e WHISPER_COMPUTE_TYPE=int8 \
  -e WHISPER_LANGUAGE=ko \
  whisper
```

첫 실행 시 Hugging Face 모델 다운로드가 발생한다.
