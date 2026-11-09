# Lumi 감정 서버(FastAPI). GPU 없는 CPU 환경(OCI Ampere ARM 포함)에서 돌아가게 CPU용 torch를 쓴다.
# 빌드 컨텍스트는 저장소 맨 위 폴더다. 모델 파일(head.pt 등)은 이미지에 굽지 않고 compose가 연결한다.
FROM python:3.11-slim
RUN useradd --system --create-home --uid 10001 lumi && mkdir -p /hf-cache && chown lumi /hf-cache
WORKDIR /app
COPY deploy/emotion-requirements.txt /tmp/req.txt
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir -r /tmp/req.txt
COPY multilingual-e5-small/scripts /app/multilingual-e5-small/scripts
COPY intent-classifier/scripts/speaker_intent_labels.py /app/intent-classifier/scripts/speaker_intent_labels.py
USER lumi
WORKDIR /app/multilingual-e5-small
ENV EMOTION_HOST=0.0.0.0 EMOTION_PORT=8002 HF_HOME=/hf-cache TOKENIZERS_PARALLELISM=false PYTHONUNBUFFERED=1
EXPOSE 8002
CMD ["python", "scripts/serve.py"]
