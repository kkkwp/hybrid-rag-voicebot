# TTS Sidecar

`OpenMOSS/MOSS-TTS-Nano` ONNX CPU 경로를 감싼 경량 TTS 사이드카다.

## Runtime

- engine: `MOSS-TTS-Nano ONNX`
- default voice: `Junhao`
- default port: `8200`
- default output: `audio/wav`

## Notes

- 첫 실행 시 Hugging Face에서 ONNX 모델 자산을 `/models`로 내려받는다.
- CPU 전용 경로를 사용한다.
- 현재 사이드카는 프로젝트 기존 계약에 맞춰 `/healthz`, `/synthesize`만 노출한다.
- 합성 요청은 built-in voice 경로를 사용하고 reference audio는 요구하지 않는다.

## Environment

```text
TTS_MODEL_DIR=/models
TTS_VOICE=Junhao
TTS_CPU_THREADS=4
TTS_MAX_NEW_FRAMES=375
TTS_MAX_TEXT_CHARS=400
TTS_VOICE_CLONE_MAX_TEXT_TOKENS=75
```

## API

- `GET /healthz`
  - `200 OK`
  - body: `{"status":"ok","engine":"moss-tts-nano-onnx","voice":"Junhao"}`
- `POST /synthesize`
  - request: `{"text":"안녕하세요. 배송 상태를 안내드리겠습니다."}`
  - response: `audio/wav`
