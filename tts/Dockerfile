FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV MOSS_TTS_ROOT=/opt/MOSS-TTS-Nano
ENV TTS_OUTPUT_DIR=/tmp/tts-output

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir --upgrade pip "setuptools<81" wheel
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.7.0 torchaudio==2.7.0

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN git clone --depth 1 https://github.com/OpenMOSS/MOSS-TTS-Nano.git ${MOSS_TTS_ROOT}

ENV PYTHONPATH="${MOSS_TTS_ROOT}:${PYTHONPATH}"

COPY app.py .

EXPOSE 8200

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8200"]
