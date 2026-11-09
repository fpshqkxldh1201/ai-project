"""Lumi 감정분석 서버 (FastAPI). 이 컴퓨터 안(127.0.0.1)에서만 받는다.

  python scripts/serve.py                      # 기본: models/emotion 의 모델, 포트 8002
  EMOTION_MODEL_DIR=경로 EMOTION_PORT=8002 python scripts/serve.py
  (선택) 사진 감정 분석: EMOTION_IMAGE_MODEL=trpakov/vit-face-expression  — 설정하지 않으면 사진 분석은 꺼진다

API
  GET  /health   → {"status": "ok", "model_version": ..., "image_enabled": ..., "gate_enabled": ..., "speaker_intent_enabled": ...}
  POST /analyze  {"type": "text"|"image"|"both", "text": "...", "image_base64": "..."}
                 → {primary_emotion, secondary_emotion, mood, valence, arousal, confidence, model_version}
  POST /speaker-intent  {"items": [{"text": "...", "relation": "parent|partner|friend|boss|colleague|other"}]}
                 → {model_version, tau, results: [{label, confidence, uncertain, top: [{label, p}, ...]}]}
                 (상대방이 한 말의 의도 12종. intent-classifier/models/speaker_intent/head.json 이 없으면 501. intent-classifier/scripts/train_speaker_intent.py 로 만든다)

개인정보: 받은 문장·사진은 저장하지 않고 로그에도 남기지 않는다(길이와 처리 시간만 기록).
"""
from __future__ import annotations

import base64
import binascii
import io
import json
import logging
import os
import sys
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal, Optional

import numpy as np
import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from emotion_labels import LABELS, N_LABELS, NEUTRAL_BELOW, neutral_summary, summarize
from emotion_model import Gate, load_model, predict_probs_and_gate
# 상대 발화 의도 분류기의 라벨·학습 코드는 intent-classifier 폴더에 있다(같은 부모 폴더 아래 형제 폴더). 환경 변수로 위치를 바꿀 수 있다.
_IC = Path(os.environ.get("INTENT_CLASSIFIER_DIR", Path(__file__).resolve().parent.parent.parent / "intent-classifier"))
for _p in (_IC / "scripts", Path(__file__).resolve().parent):
    if _p.is_dir() and str(_p) not in sys.path:
        sys.path.append(str(_p))
from speaker_intent_labels import LABELS as INTENT_LABELS
from speaker_intent_labels import model_text as intent_text

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = Path(os.environ.get("EMOTION_MODEL_DIR", ROOT / "models" / "emotion"))
IMAGE_MODEL = os.environ.get("EMOTION_IMAGE_MODEL", "").strip()
SPEAKER_DIR = Path(os.environ.get("SPEAKER_INTENT_MODEL_DIR", _IC / "models" / "speaker_intent"))
MAX_TEXT_CHARS = 2000
MAX_SPEAKER_ITEMS = 12
MAX_SPEAKER_CHARS = 500
MAX_IMAGE_B64 = 6_000_000   # base64 문자 수 (원본 약 4.5MB)
TEXT_WEIGHT = 0.6           # 글과 사진을 함께 볼 때 글의 비중

# 얼굴 표정 모델(FER 7종)의 라벨 → 44개 정서 번호
FER_TO_LABEL = {"angry": "화남/분노", "disgust": "역겨움/징그러움", "fear": "공포/무서움", "happy": "행복",
                "neutral": "없음", "sad": "슬픔", "surprise": "놀람"}

log = logging.getLogger("emotion-server")
state: dict = {}
lock = threading.Lock()


def image_probs(pipe, image_b64: str) -> np.ndarray:
    """사진 → 44개 정서 확률 벡터(표정 모델이 아는 정서에만 값이 있다)."""
    from PIL import Image

    if "," in image_b64[:80]:  # data:image/jpeg;base64,.... 형태도 받는다
        image_b64 = image_b64.split(",", 1)[1]
    try:
        raw = base64.b64decode(image_b64, validate=True)
        img = Image.open(io.BytesIO(raw)).convert("RGB")
    except (binascii.Error, ValueError, OSError) as e:
        raise HTTPException(status_code=422, detail="이미지를 읽을 수 없습니다") from e
    vec = np.zeros(N_LABELS, dtype="float32")
    for item in pipe(img, top_k=None):
        name = FER_TO_LABEL.get(str(item["label"]).lower())
        if name is not None:
            vec[LABELS.index(name)] = float(item["score"])
    return vec


def load_speaker_intent() -> None:
    """상대방 발화 의도 분류기(head.json). 없거나 라벨이 다르면 이 기능만 끄고 서버는 계속 뜬다."""
    path = SPEAKER_DIR / "head.json"
    state["speaker"] = None
    if not path.exists():
        log.info("상대 발화 의도 분류기 꺼짐 (%s 없음 — intent-classifier에서 python train_speaker_intent.py)", path)
        return
    try:
        d = json.loads(path.read_text(encoding="utf-8"))
        if d.get("labels") != INTENT_LABELS:
            raise ValueError("저장된 의도 라벨이 현재 speaker_intent_labels.py와 다릅니다. 다시 학습하세요")
        state["speaker"] = {"W": np.asarray(d["coef"], dtype="float64"), "b": np.asarray(d["intercept"], dtype="float64"),
                            "tau": float(d["tau"]), "T": float(d.get("temperature", 1.0)), "mode": d.get("relation_mode", "prefix"), "version": d["model_version"]}
        enc = d.get("encoder_name")
        if enc and enc != state["meta"].get("encoder_name"):
            log.warning("의도 분류기는 인코더 %s 로 학습됐지만 서버는 %s 를 씁니다 — 결과가 틀어질 수 있습니다", enc, state["meta"].get("encoder_name"))
        log.info("상대 발화 의도 분류기 켜짐: %s (애매함 기준 %.2f)", d["model_version"], state["speaker"]["tau"])
    except Exception as e:
        log.warning("상대 발화 의도 분류기를 불러오지 못해 끕니다: %s", e)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if not (MODEL_DIR / "meta.json").exists():
        sys.exit(f"모델을 찾을 수 없습니다: {MODEL_DIR}\n먼저 python scripts/train.py --mode head 로 학습하세요.")
    model, meta = load_model(MODEL_DIR)
    gate = Gate.load(MODEL_DIR)
    state.update(model=model, meta=meta, pipe=None, gate=gate)
    log.info("감정 모델 로드: %s (%s)", meta["model_version"], MODEL_DIR)
    log.info("명령 게이트 %s", f"켜짐 (기준 {gate.threshold})" if gate else "없음 — 명령 문장도 감정으로 읽힐 수 있습니다(train.py로 다시 학습하면 생깁니다)")
    load_speaker_intent()
    if IMAGE_MODEL:
        try:
            from transformers import pipeline

            state["pipe"] = pipeline("image-classification", model=IMAGE_MODEL)
            log.info("사진 감정 분석 켜짐: %s", IMAGE_MODEL)
        except Exception as e:  # 사진 모델이 없어도 글 분석은 계속한다
            log.warning("사진 감정 모델을 불러오지 못해 사진 분석을 끕니다: %s", e)
    else:
        log.info("사진 감정 분석 꺼짐 (EMOTION_IMAGE_MODEL 미설정)")
    yield


app = FastAPI(title="Lumi emotion server", lifespan=lifespan)


class AnalyzeRequest(BaseModel):
    type: Literal["text", "image", "both"] = "text"
    text: Optional[str] = Field(default=None, max_length=MAX_TEXT_CHARS)
    image_base64: Optional[str] = Field(default=None, max_length=MAX_IMAGE_B64)


@app.get("/health")
def health():
    return {"status": "ok", "model_version": state["meta"]["model_version"], "image_enabled": state.get("pipe") is not None,
            "gate_enabled": state.get("gate") is not None, "speaker_intent_enabled": state.get("speaker") is not None}


class SpeakerItem(BaseModel):
    text: str = Field(max_length=MAX_SPEAKER_CHARS)
    relation: Optional[str] = "other"


class SpeakerRequest(BaseModel):
    items: list[SpeakerItem] = Field(min_length=1, max_length=MAX_SPEAKER_ITEMS)


@app.post("/speaker-intent")
def speaker_intent(req: SpeakerRequest):
    """상대방이 한 말(들)의 의도. 받은 글은 저장하지 않고 로그에도 남기지 않는다(개수와 처리 시간만)."""
    t0 = time.time()
    sp = state.get("speaker")
    if sp is None:
        raise HTTPException(status_code=501, detail="상대 발화 의도 분류기가 꺼져 있습니다")
    texts = [intent_text(it.relation, it.text, sp["mode"]) for it in req.items]
    if any(not it.text.strip() for it in req.items):
        raise HTTPException(status_code=422, detail="text가 비어 있습니다")
    with lock, torch.no_grad():
        emb = state["model"].encoder.embed(texts).float().cpu().numpy().astype("float64")
    z = (emb @ sp["W"].T + sp["b"]) / sp["T"]
    z -= z.max(1, keepdims=True)
    P = np.exp(z)
    P /= P.sum(1, keepdims=True)
    results = []
    for row in P:
        order = np.argsort(-row)
        results.append({"label": INTENT_LABELS[int(order[0])], "confidence": round(float(row[order[0]]), 4),
                        "uncertain": bool(row[order[0]] < sp["tau"]),
                        "top": [{"label": INTENT_LABELS[int(j)], "p": round(float(row[j]), 4)} for j in order[:3]]})
    log.info("speaker-intent items=%d %.0fms", len(texts), (time.time() - t0) * 1000)
    return {"model_version": sp["version"], "tau": sp["tau"], "results": results}


@app.post("/analyze")
def analyze(req: AnalyzeRequest):
    t0 = time.time()
    text = (req.text or "").strip()
    want_text = req.type in ("text", "both")
    want_image = req.type in ("image", "both")
    if want_text and not text:
        raise HTTPException(status_code=422, detail="text가 비어 있습니다")
    if want_image and not req.image_base64:
        raise HTTPException(status_code=422, detail="image_base64가 필요합니다")
    use_image = want_image and state.get("pipe") is not None
    if req.type == "image" and not use_image:
        raise HTTPException(status_code=501, detail="사진 감정 분석이 꺼져 있습니다")

    gate = state.get("gate")
    gate_prob, gated = None, False
    with lock:
        p_text = None
        if want_text:
            P, G = predict_probs_and_gate(state["model"], gate, [text])
            p_text = P[0]
            if G is not None:
                gate_prob = float(G[0])
                gated = gate_prob >= gate.threshold
        p_img = image_probs(state["pipe"], req.image_base64) if use_image else None
    if gated:
        p_text = None  # 명령·요청 문장: 글에서는 감정을 읽지 않는다(사진이 함께 오면 사진 표정만 본다)
    if p_text is not None and p_img is not None:
        probs = TEXT_WEIGHT * p_text + (1 - TEXT_WEIGHT) * p_img
    else:
        probs = p_text if p_text is not None else p_img
    if probs is None:
        result = neutral_summary(gate_prob)
    else:
        result = summarize(probs, state["meta"].get("neutral_below", NEUTRAL_BELOW))
    result["model_version"] = state["meta"]["model_version"]
    if use_image or gated:
        result["ambiguous"] = False  # 이미 사진을 함께 봤거나 명령 문장이면 사진을 다시 청하지 않는다
    log.info("analyze type=%s chars=%d image=%s gated=%s %.0fms", req.type, len(text), use_image, gated, (time.time() - t0) * 1000)
    return result


if __name__ == "__main__":
    import uvicorn

    torch.set_num_threads(max(1, (os.cpu_count() or 2) // 2))
    # 기본은 이 컴퓨터 안(127.0.0.1)에서만 받는다. Docker 안에서는 EMOTION_HOST=0.0.0.0 으로 바꾸되,
    # 컨테이너 포트를 호스트에 publish 하지 않고 같은 compose 네트워크의 Spring만 접근하게 둔다.
    uvicorn.run(app, host=os.environ.get("EMOTION_HOST", "127.0.0.1"), port=int(os.environ.get("EMOTION_PORT", "8002")), log_level="warning")
