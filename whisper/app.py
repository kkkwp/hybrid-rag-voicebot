import logging
import os
import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from faster_whisper import WhisperModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("whisper")

WHISPER_MODEL = os.getenv("WHISPER_MODEL", "large")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
WHISPER_LANGUAGE = os.getenv("WHISPER_LANGUAGE", "ko")
FFMPEG_TIMEOUT_SECONDS = int(os.getenv("FFMPEG_TIMEOUT_SECONDS", "10"))

app = FastAPI(title="STT Sidecar", version="0.1.0")
model: WhisperModel | None = None


@app.on_event("startup")
def load_model() -> None:
    global model
    logger.info(
        "loading faster-whisper model=%s device=%s compute_type=%s",
        WHISPER_MODEL,
        WHISPER_DEVICE,
        WHISPER_COMPUTE_TYPE,
    )
    model = WhisperModel(
        WHISPER_MODEL,
        device=WHISPER_DEVICE,
        compute_type=WHISPER_COMPUTE_TYPE,
    )
    logger.info("faster-whisper model loaded")


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {
        "status": "ok",
        "model": WHISPER_MODEL,
    }


@app.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    initial_prompt: str = Form(default=""),
) -> dict:
    if model is None:
        raise HTTPException(status_code=503, detail="Whisper model is not loaded")

    input_path: str | None = None
    wav_path: str | None = None
    try:
        suffix = Path(file.filename or "input.webm").suffix or ".webm"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as input_file:
            input_path = input_file.name
            while chunk := await file.read(1024 * 1024):
                input_file.write(chunk)

        with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as wav_file:
            wav_path = wav_file.name

        convert_to_wav(input_path, wav_path)
        transcribe_kwargs = {
            "language": WHISPER_LANGUAGE,
            "vad_filter": True,
        }
        if initial_prompt:
            transcribe_kwargs["initial_prompt"] = initial_prompt
        segments_iter, info = model.transcribe(wav_path, **transcribe_kwargs)
        segments = [
            {
                "start": float(segment.start),
                "end": float(segment.end),
                "text": segment.text.strip(),
            }
            for segment in segments_iter
        ]
        text = " ".join(segment["text"] for segment in segments).strip()
        logger.info(
            "transcription completed language=%s duration=%.3f segments=%d textLength=%d",
            info.language,
            float(info.duration or 0.0),
            len(segments),
            len(text),
        )
        return {
            "text": text,
            "language": info.language,
            "duration": float(info.duration or 0.0),
            "segments": segments,
        }
    finally:
        cleanup(input_path)
        cleanup(wav_path)


def convert_to_wav(input_path: str, wav_path: str) -> None:
    command = [
        "ffmpeg",
        "-y",
        "-i",
        input_path,
        "-ac",
        "1",
        "-ar",
        "16000",
        "-f",
        "wav",
        wav_path,
    ]
    try:
        completed = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=FFMPEG_TIMEOUT_SECONDS,
        )
        if completed.stderr:
            logger.debug("ffmpeg stderr: %s", completed.stderr[-1000:])
    except subprocess.TimeoutExpired as error:
        raise HTTPException(status_code=422, detail="ffmpeg conversion timed out") from error
    except subprocess.CalledProcessError as error:
        logger.warning("ffmpeg failed: %s", (error.stderr or "")[-1000:])
        raise HTTPException(status_code=422, detail="ffmpeg conversion failed") from error


def cleanup(path: str | None) -> None:
    if not path:
        return
    try:
        os.unlink(path)
    except FileNotFoundError:
        return
    except OSError as error:
        logger.warning("failed to delete temp file path=%s error=%s", path, error)
