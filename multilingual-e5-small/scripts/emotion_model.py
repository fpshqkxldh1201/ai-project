"""학습(train.py)과 서버(serve.py)가 같이 쓰는 모델 정의.

구조: 다국어 문장 인코더(기본 multilingual-e5-small) → 평균 풀링 → L2 정규화 → MLP 헤드 → 44개 정서 점수.
정서는 한 문장에 여러 개가 동시에 있을 수 있으므로(multi-label) 마지막에 softmax가 아니라 sigmoid를 쓴다.
학습 때 쓴 풀링·접두어와 서버가 쓰는 것이 달라지면 결과가 어긋나므로 두 곳이 이 파일을 함께 쓴다.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F

from emotion_labels import LABELS, N_LABELS

DEFAULT_ENCODER = "intfloat/multilingual-e5-small"
DEFAULT_PREFIX = "query: "  # e5 계열은 입력 앞에 이 접두어를 붙이는 것이 규칙
DEFAULT_MAX_LEN = 64


class HFEncoder(nn.Module):
    """HuggingFace 인코더 + 평균 풀링 + L2 정규화. embed(texts) -> (B, D)."""

    def __init__(self, name_or_path: str, prefix: str = DEFAULT_PREFIX, max_len: int = DEFAULT_MAX_LEN):
        super().__init__()
        from transformers import AutoModel, AutoTokenizer

        self.name, self.prefix, self.max_len = name_or_path, prefix, max_len
        self.tok = AutoTokenizer.from_pretrained(name_or_path)
        self.model = AutoModel.from_pretrained(name_or_path)
        self.dim = self.model.config.hidden_size

    def embed(self, texts):
        dev = next(self.model.parameters()).device
        batch = self.tok([self.prefix + t for t in texts], padding=True, truncation=True,
                         max_length=self.max_len, return_tensors="pt").to(dev)
        out = self.model(**batch).last_hidden_state
        mask = batch["attention_mask"].unsqueeze(-1).to(out.dtype)
        pooled = (out * mask).sum(1) / mask.sum(1).clamp(min=1)
        return F.normalize(pooled, dim=-1)


def make_head(dim: int, hidden: int = 256, dropout: float = 0.3) -> nn.Module:
    return nn.Sequential(nn.Linear(dim, hidden), nn.GELU(), nn.Dropout(dropout), nn.Linear(hidden, N_LABELS))


class EmotionModel(nn.Module):
    def __init__(self, encoder: HFEncoder, hidden: int = 256, dropout: float = 0.3):
        super().__init__()
        self.encoder = encoder
        self.hidden = hidden
        self.head = make_head(encoder.dim, hidden, dropout)

    def forward(self, texts):
        return self.head(self.encoder.embed(texts))


@torch.no_grad()
def predict_probs(model: EmotionModel, texts, batch_size: int = 64):
    """문장 목록 → (N, 44) 확률(numpy). 길이순으로 묶어 패딩 낭비를 줄이고, 결과는 원래 순서로 돌려준다."""
    import numpy as np

    model.eval()
    order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
    out = np.zeros((len(texts), N_LABELS), dtype="float32")
    for s in range(0, len(order), batch_size):
        idx = order[s:s + batch_size]
        out[idx] = torch.sigmoid(model([texts[i] for i in idx])).float().cpu().numpy()
    return out


class Gate:
    """'명령·요청 문장인가'를 가르는 작은 선형 분류기(인코더 임베딩 위의 로지스틱 회귀).

    Lumi의 의도 분류 데이터(일정·알림·노트·검색 같은 명령 + 범위 밖 요청)를 '명령', 감정을 털어놓는 대화(chat_emotion)와
    KOTE의 감정 문장을 '명령 아님'으로 학습한다. 명령이라고 확신하면(prob >= threshold) 감정 분석 결과를 중립으로 돌려
    "오늘 일정 알려줘"가 들뜬 감정으로 읽히거나 감정 기록·안부 알림에 쌓이는 일을 막는다.
    """

    FILE = "gate.json"

    def __init__(self, coef, intercept: float, threshold: float, info: dict | None = None):
        self.coef = [float(x) for x in coef]
        self.intercept = float(intercept)
        self.threshold = float(threshold)
        self.info = info or {}

    def prob(self, emb):
        """emb: (N, D) 임베딩(numpy) → (N,) '명령일 확률'."""
        import numpy as np

        z = np.asarray(emb, dtype="float64") @ np.asarray(self.coef, dtype="float64") + self.intercept
        return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))

    def save(self, out_dir: Path) -> None:
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / self.FILE).write_text(json.dumps(
            {"coef": self.coef, "intercept": self.intercept, "threshold": self.threshold, "info": self.info},
            ensure_ascii=False), encoding="utf-8")

    @classmethod
    def load(cls, out_dir: Path):
        path = Path(out_dir) / cls.FILE
        if not path.exists():
            return None
        d = json.loads(path.read_text(encoding="utf-8"))
        return cls(d["coef"], d["intercept"], d["threshold"], d.get("info"))

    @staticmethod
    def remove(out_dir: Path) -> None:
        """게이트 없이 다시 학습했을 때 예전 게이트가 남아 새 모델에 섞이지 않게 지운다."""
        (Path(out_dir) / Gate.FILE).unlink(missing_ok=True)


@torch.no_grad()
def predict_probs_and_gate(model: EmotionModel, gate, texts, batch_size: int = 64):
    """predict_probs와 같지만 임베딩을 한 번만 계산해 게이트 확률도 함께 돌려준다. 반환: (P (N,44), G (N,) 또는 None)."""
    import numpy as np

    model.eval()
    order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
    out = np.zeros((len(texts), N_LABELS), dtype="float32")
    g = np.zeros(len(texts), dtype="float64") if gate is not None else None
    for s in range(0, len(order), batch_size):
        idx = order[s:s + batch_size]
        emb = model.encoder.embed([texts[i] for i in idx])
        out[idx] = torch.sigmoid(model.head(emb)).float().cpu().numpy()
        if gate is not None:
            g[idx] = gate.prob(emb.float().cpu().numpy())
    return out, g


@torch.no_grad()
def embed_plain(encoder: HFEncoder, texts, batch_size: int = 64):
    """캐시 없이 문장 임베딩 (N, D). 미세조정한 인코더의 임베딩에는 예전 캐시를 쓰면 안 되므로 따로 둔다."""
    import numpy as np

    encoder.eval()
    order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
    out = np.zeros((len(texts), encoder.dim), dtype="float32")
    for s in range(0, len(order), batch_size):
        idx = order[s:s + batch_size]
        out[idx] = encoder.embed([texts[i] for i in idx]).float().cpu().numpy()
    return out


# ───────────────────────── 저장 / 불러오기 ─────────────────────────
def save_model(out_dir: Path, model: EmotionModel, meta: dict, save_encoder: bool) -> None:
    """head.pt + meta.json (+ 미세조정한 인코더는 encoder/ 폴더)."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    torch.save(model.head.state_dict(), out_dir / "head.pt")
    meta = dict(meta)
    if save_encoder:
        enc_dir = out_dir / "encoder"
        model.encoder.model.save_pretrained(enc_dir)
        model.encoder.tok.save_pretrained(enc_dir)
        meta["encoder_path"] = "encoder"
    else:
        meta["encoder_path"] = None
    meta.update(labels=LABELS, hidden=model.hidden, dim=model.encoder.dim,
                prefix=model.encoder.prefix, max_len=model.encoder.max_len)
    (out_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def load_model(out_dir: Path, device: str = "cpu"):
    """save_model로 저장한 폴더에서 모델을 복원한다. 반환: (model, meta)."""
    out_dir = Path(out_dir)
    meta = json.loads((out_dir / "meta.json").read_text(encoding="utf-8"))
    if meta.get("labels") != LABELS:
        raise RuntimeError("저장된 라벨 순서가 현재 emotion_labels.py와 다릅니다. 모델을 다시 학습하세요.")
    source = str(out_dir / meta["encoder_path"]) if meta.get("encoder_path") else meta["encoder_name"]
    encoder = HFEncoder(source, meta["prefix"], meta["max_len"])
    model = EmotionModel(encoder, hidden=meta["hidden"])
    model.head.load_state_dict(torch.load(out_dir / "head.pt", map_location="cpu"))
    return model.to(device).eval(), meta
