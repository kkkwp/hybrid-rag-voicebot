import logging
import os
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from huggingface_hub import snapshot_download
from pydantic import BaseModel

from onnx_tts_runtime import (
    DEFAULT_BROWSER_ONNX_CODEC_REPO_ID,
    DEFAULT_BROWSER_ONNX_CODEC_DIR,
    DEFAULT_BROWSER_ONNX_MODEL_DIR,
    DEFAULT_BROWSER_ONNX_TTS_REPO_ID,
    DEFAULT_BROWSER_ONNX_TTS_DIR,
    OnnxTtsRuntime,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("tts")

MODEL_DIR = os.getenv("TTS_MODEL_DIR") or None
OUTPUT_DIR = Path(os.getenv("TTS_OUTPUT_DIR", "/tmp/tts-output"))
VOICE = os.getenv("TTS_VOICE", "Junhao")
CPU_THREADS = int(os.getenv("TTS_CPU_THREADS", "4"))
MAX_TEXT_CHARS = int(os.getenv("TTS_MAX_TEXT_CHARS", "400"))
MAX_NEW_FRAMES = int(os.getenv("TTS_MAX_NEW_FRAMES", "375"))
VOICE_CLONE_MAX_TEXT_TOKENS = int(os.getenv("TTS_VOICE_CLONE_MAX_TEXT_TOKENS", "75"))

app = FastAPI(title="MOSS-TTS-Nano ONNX Sidecar", version="0.3.0")
runtime: OnnxTtsRuntime | None = None


class SynthesizeRequest(BaseModel):
    text: str


@app.on_event("startup")
def load_runtime() -> None:
    global runtime
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    ensure_onnx_assets()
    logger.info(
        "loading moss-tts-nano onnx runtime model_dir=%s voice=%s cpu_threads=%s",
        MODEL_DIR or "<default>",
        VOICE,
        CPU_THREADS,
    )
    runtime = OnnxTtsRuntime(
        model_dir=MODEL_DIR,
        thread_count=max(1, CPU_THREADS),
        max_new_frames=MAX_NEW_FRAMES,
        output_dir=OUTPUT_DIR,
    )
    logger.info("moss-tts-nano onnx runtime loaded")


def ensure_onnx_assets() -> None:
    model_root = Path(MODEL_DIR).expanduser().resolve() if MODEL_DIR else DEFAULT_BROWSER_ONNX_MODEL_DIR.resolve()
    tts_dir = model_root / DEFAULT_BROWSER_ONNX_TTS_DIR.name
    codec_dir = model_root / DEFAULT_BROWSER_ONNX_CODEC_DIR.name
    tts_meta = tts_dir / "tts_browser_onnx_meta.json"
    codec_meta = codec_dir / "codec_browser_onnx_meta.json"
    manifest = tts_dir / "browser_poc_manifest.json"
    tokenizer_model = tts_dir / "tokenizer.model"

    if all(path.is_file() for path in (tts_meta, codec_meta, manifest, tokenizer_model)):
        return

    logger.info("onnx assets missing or incomplete under model_root=%s; downloading", model_root)
    tts_dir.mkdir(parents=True, exist_ok=True)
    codec_dir.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=DEFAULT_BROWSER_ONNX_TTS_REPO_ID,
        local_dir=str(tts_dir),
        allow_patterns=["*.onnx", "*.data", "*.json", "tokenizer.model"],
    )
    snapshot_download(
        repo_id=DEFAULT_BROWSER_ONNX_CODEC_REPO_ID,
        local_dir=str(codec_dir),
        allow_patterns=["*.onnx", "*.data", "*.json"],
    )


@app.get("/healthz")
def healthz() -> dict[str, str]:
    if runtime is None:
        raise HTTPException(status_code=503, detail="MOSS-TTS-Nano ONNX runtime is not loaded")
    return {
        "status": "ok",
        "engine": "moss-tts-nano-onnx",
        "voice": VOICE,
    }


@app.post("/synthesize")
def synthesize(payload: SynthesizeRequest) -> Response:
    if runtime is None:
        raise HTTPException(status_code=503, detail="MOSS-TTS-Nano ONNX runtime is not loaded")

    text = (payload.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text is required")
    if len(text) > MAX_TEXT_CHARS:
        raise HTTPException(status_code=413, detail="text payload too large")

    try:
        result = runtime.synthesize(
            text=text,
            voice=VOICE,
            prompt_audio_path=None,
            sample_mode="fixed",
            do_sample=True,
            streaming=False,
            max_new_frames=MAX_NEW_FRAMES,
            voice_clone_max_text_tokens=VOICE_CLONE_MAX_TEXT_TOKENS,
            enable_wetext=False,
            enable_normalize_tts_text=True,
        )
    except Exception as error:
        logger.exception("moss-tts-nano synthesis failed")
        raise HTTPException(status_code=502, detail="tts inference failed") from error

    audio_path = Path(str(result["audio_path"])).resolve()
    try:
        audio_bytes = audio_path.read_bytes()
    except Exception as error:
        logger.exception("failed to read synthesized wav file path=%s", audio_path)
        raise HTTPException(status_code=502, detail="tts output read failed") from error

    return Response(
        content=audio_bytes,
        media_type="audio/wav",
        headers={"X-Audio-Bytes": str(len(audio_bytes))},
    )
