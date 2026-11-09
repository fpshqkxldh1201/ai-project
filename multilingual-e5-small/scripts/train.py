"""감정 분류기 학습: 다국어 인코더(기본 multilingual-e5-small) 위에 44개 정서(multi-label) 분류기를 직접 학습한다.

  python scripts/train.py --mode head                          # 인코더 고정 + MLP 헤드 (CPU로 10~20분, 처음엔 이것부터)
  python scripts/train.py --mode finetune --max-train 8000     # 인코더까지 미세조정 (CPU에서 시간이 걸림 — 아래 설명 참고)
  python scripts/train.py --mode head --final                  # 테스트셋 최종 평가 (모델당 한 번만!)

추가 학습 데이터(감정이 없는 명령 문장)와 명령 게이트
  - data/extra_*.csv 가 있으면(python scripts/prepare_extra.py 로 만든다) 자동으로 쓴다. 끄려면 --no-extra.
  - 명령 문장(일정 알려줘 등)을 '없음(감정 없음)'으로 학습시키고(기본 3번 반복), 검증용 명령 문장을 검증셋에 더해
    조기 종료와 중립 기준을 정한다. KOTE 검증 지표(F1 등)는 KOTE 검증 문장만으로 계산해 예전 결과와 비교할 수 있게 둔다.
  - 같은 문장 데이터로 '명령 게이트'(명령·요청이면 중립 처리)도 학습한다. 끄려면 --no-gate.

데이터 사용 원칙
  - train: 학습 / val: 에폭 선택(조기 종료)·임계값 선택 / test: --final 로 단 한 번.
  - data/lumi_check.csv(Lumi 대화체 점검 문장)는 학습·튜닝에 쓰지 않고, 도메인이 다른 데이터에서의 성능을 보기 위한 참고 지표다.

결과: models/emotion/ (head.pt, meta.json, 미세조정 시 encoder/), results/emotion_<mode>.json·.md·_history.csv
"""
from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import math
import os
import random
import re
import sys
import time
from datetime import datetime
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, roc_auc_score

from emotion_labels import (LABELS, NEUTRAL_BELOW, N_LABELS, NONE_IDX, polarity, summarize, summarize_with_gate,
                            target_valence)
from emotion_model import (DEFAULT_ENCODER, DEFAULT_MAX_LEN, DEFAULT_PREFIX, EmotionModel, Gate, HFEncoder, embed_plain,
                           make_head, predict_probs, predict_probs_and_gate, save_model)
from prepare_data import KOTE_DIR, read_tsv

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models" / "emotion"
RESULTS = Path(os.environ.get("EMOTION_RESULTS_DIR", ROOT / "results"))
CACHE = Path(os.environ.get("EMOTION_CACHE_DIR", ROOT / "data" / "cache"))
CHECK = ROOT / "data" / "lumi_check.csv"
EXTRA_DIR = Path(os.environ.get("EMOTION_EXTRA_DIR", ROOT / "data"))
FINAL_LOG = RESULTS / "final_log.txt"


# ───────────────────────── 데이터 ─────────────────────────
def load_split(name: str, limit: int = 0):
    rows = read_tsv(KOTE_DIR / f"{name}.tsv")
    if limit:
        rows = rows[:limit]
    texts = [r[1] for r in rows]
    Y = np.zeros((len(rows), N_LABELS), dtype="float32")
    for k, (_, _, ids) in enumerate(rows):
        Y[k, ids] = 1.0
    return texts, Y


def norm_text(s: str) -> str:
    """띄어쓰기·문장부호·대소문자 차이를 무시하고 같은 문장인지 비교하기 위한 형태."""
    return re.sub(r"[\W_]+", "", s.lower())


def _read_texts(path: Path):
    if not path.exists():
        return []
    with open(path, newline="", encoding="utf-8-sig") as f:
        return [r["text"].strip() for r in csv.DictReader(f) if (r.get("text") or "").strip()]


def load_extras() -> dict:
    """data/extra_*.csv(prepare_extra.py가 만든 감정 없는 명령 문장 등)를 읽는다.
    Lumi 점검 문장(data/lumi_check.csv)과 같은 문장은 여기서도 한 번 더 뺀다 — 점검이 학습에 새면 안 된다."""
    ex = {k: _read_texts(EXTRA_DIR / f"extra_{k}.csv") for k in ("command_train", "command_val", "chat_train", "chat_val")}
    check = {norm_text(t) for t in _read_texts(CHECK)}
    dropped = 0
    for k in list(ex):
        keep = []
        for t in ex[k]:
            if norm_text(t) in check:
                dropped += 1
            else:
                keep.append(t)
        ex[k] = keep
    ex["dropped_check"] = dropped
    return ex


def none_rows(n: int):
    """'감정 없음'만 켜진 정답 n줄."""
    Y = np.zeros((n, N_LABELS), dtype="float32")
    Y[:, NONE_IDX] = 1.0
    return Y


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


class Clock:
    """진행률과 남은 시간 표시."""

    def __init__(self):
        self.t0 = time.time()

    def eta(self, done: int, total: int) -> str:
        if done <= 0:
            return "?"
        sec = (time.time() - self.t0) / done * (total - done)
        return f"{int(sec // 60)}분 {int(sec % 60)}초"


# ───────────────────────── 지표 ─────────────────────────
def bce_from_probs(P, Y) -> float:
    P = np.clip(P, 1e-6, 1 - 1e-6)
    return float(-(Y * np.log(P) + (1 - Y) * np.log(1 - P)).mean())


def best_threshold(P, Y):
    grid = [round(t, 2) for t in np.arange(0.20, 0.65, 0.05)]
    scores = [f1_score(Y, P >= t, average="micro", zero_division=0) for t in grid]
    return grid[int(np.argmax(scores))]


def fit_neutral_threshold(P, Y):
    """'감정이 있는 문장'과 '감정 없음(없음 라벨만)' 문장을 가장 잘 가르는 확률 기준을 검증셋에서 찾는다(Youden J).
    KOTE는 한 댓글에 정서가 여러 개 붙는 데이터라 정서 확률이 전반적으로 높은 편이므로 고정값 대신 데이터로 정한다.
    감정을 잘못 짚는 것이 놓치는 것보다 나쁘므로 NEUTRAL_BELOW(0.30)보다 낮게는 잡지 않는다."""
    Y2 = Y.copy()
    Y2[:, NONE_IDX] = 0
    emo = Y2.sum(1) > 0
    P2 = P.copy()
    P2[:, NONE_IDX] = -1.0
    score = P2.max(1)
    best_j, best_t = -1.0, NEUTRAL_BELOW
    for t in np.arange(0.20, 0.82, 0.02):
        pred = score >= t
        tpr = float(pred[emo].mean()) if emo.any() else 0.0
        fpr = float(pred[~emo].mean()) if (~emo).any() else 0.0
        if tpr - fpr > best_j:
            best_j, best_t = tpr - fpr, float(t)
    return round(max(best_t, NEUTRAL_BELOW), 2), round(best_j, 3), int((~emo).sum())


def evaluate_probs(P, Y, thr: float, neutral_below: float = NEUTRAL_BELOW) -> dict:
    """multi-label F1(임계값 thr), top-1 적중률, 긍정/부정 방향 정확도, BCE 손실."""
    pred = P >= thr
    out = {
        "threshold": thr,
        "micro_f1": float(f1_score(Y, pred, average="micro", zero_division=0)),
        "macro_f1": float(f1_score(Y, pred, average="macro", zero_division=0)),
        "bce_loss": bce_from_probs(P, Y),
    }
    P2 = P.copy()
    P2[:, NONE_IDX] = -1.0
    top = P2.argmax(1)
    Y2 = Y.copy()
    Y2[:, NONE_IDX] = 0
    has = Y2.sum(1) > 0
    hit = Y[np.arange(len(Y)), top] == 1
    out["top1_hit"] = float(hit[has].mean()) if has.any() else float("nan")
    ok = n = 0
    for i in range(len(P)):
        tv = target_valence(np.flatnonzero(Y[i]).tolist())
        if tv is None or abs(tv) < 0.2:
            continue
        n += 1
        ok += polarity(summarize(P[i], neutral_below)["valence"]) == polarity(tv)
    out["polarity_acc"] = ok / n if n else float("nan")
    out["polarity_n"] = n
    return out


def eval_check(model: EmotionModel, neutral_below: float = NEUTRAL_BELOW, gate=None):
    """Lumi 대화체 점검 문장(data/lumi_check.csv)에서의 결과. 튜닝에는 쓰지 않는다."""
    if not CHECK.exists():
        return None
    with open(CHECK, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    P, G = predict_probs_and_gate(model, gate, [r["text"] for r in rows])
    items, by_lang, by_exp = [], {}, {}
    for k, (r, p) in enumerate(zip(rows, P)):
        s = summarize_with_gate(p, neutral_below, None if G is None else G[k], None if gate is None else gate.threshold)
        pol_ok = polarity(s["valence"]) == r["expected_polarity"]
        mood_ok = s["mood"] in r["expected_moods"].split("|")
        items.append({"id": r["id"], "text": r["text"], "lang": r["lang"], "expected_polarity": r["expected_polarity"],
                      "expected_moods": r["expected_moods"], "pred_primary": s["primary_emotion"], "pred_mood": s["mood"],
                      "pred_valence": s["valence"], "confidence": s["confidence"], "polarity_ok": pol_ok, "mood_ok": mood_ok})
        d = by_lang.setdefault(r["lang"], [0, 0, 0])
        d[0] += 1
        d[1] += pol_ok
        d[2] += mood_ok
        e = by_exp.setdefault(r["expected_polarity"], [0, 0, 0, 0])  # 문장 수, 방향 일치, 기분 일치, 중립으로 예측
        e[0] += 1
        e[1] += pol_ok
        e[2] += mood_ok
        e[3] += s["mood"] == "neutral"
    n = len(items)
    return {
        "n": n,
        "polarity_acc": sum(i["polarity_ok"] for i in items) / n,
        "mood_acc": sum(i["mood_ok"] for i in items) / n,
        "by_lang": {k: {"n": v[0], "polarity_acc": v[1] / v[0], "mood_acc": v[2] / v[0]} for k, v in by_lang.items()},
        "by_expected": {k: {"n": v[0], "polarity_acc": v[1] / v[0], "mood_acc": v[2] / v[0], "pred_neutral": v[3] / v[0]}
                        for k, v in by_exp.items()},
        "failures": [i for i in items if not (i["polarity_ok"] and i["mood_ok"])],
    }


# ───────────────────────── 헤드 모드 ─────────────────────────
@torch.no_grad()
def embed_split(encoder: HFEncoder, texts, tag: str, device, batch_size: int = 64):
    """문장 임베딩 (N, D). 한 번 계산한 것은 data/cache에 저장해 다음 실행부터 재사용한다."""
    sig = hashlib.sha1(("\n".join(texts)).encode("utf-8")).hexdigest()[:10]
    cfg = hashlib.sha1(f"{encoder.name}|{encoder.prefix}|{encoder.max_len}".encode()).hexdigest()[:8]
    path = CACHE / f"emb_{cfg}_{tag}_{len(texts)}_{sig}.npy"
    if path.exists():
        return np.load(path)
    encoder.eval()
    order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
    out = np.zeros((len(texts), encoder.dim), dtype="float32")
    clock = Clock()
    nb = math.ceil(len(order) / batch_size)
    for b in range(nb):
        idx = order[b * batch_size:(b + 1) * batch_size]
        out[idx] = encoder.embed([texts[i] for i in idx]).float().cpu().numpy()
        if b % 40 == 0 or b == nb - 1:
            print(f"  임베딩 {tag}: {b + 1}/{nb} 묶음  (남은 시간 약 {clock.eta(b + 1, nb)})", flush=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    np.save(path, out)
    return out


def train_head(Xtr, Ytr, Xva, Yva, seed, hidden, epochs, patience, bs, lr, device, log):
    set_seed(seed)
    head = make_head(Xtr.shape[1], hidden).to(device)
    opt = torch.optim.AdamW(head.parameters(), lr=lr, weight_decay=1e-2)
    Xtr_t, Ytr_t = torch.tensor(Xtr, device=device), torch.tensor(Ytr, device=device)
    Xva_t, Yva_t = torch.tensor(Xva, device=device), torch.tensor(Yva, device=device)
    best, best_ep, best_state, bad, hist = float("inf"), 0, None, 0, []
    gen = torch.Generator().manual_seed(seed)
    for ep in range(1, epochs + 1):
        head.train()
        perm = torch.randperm(len(Xtr_t), generator=gen).tolist()
        tl = 0.0
        for i in range(0, len(perm), bs):
            idx = perm[i:i + bs]
            loss = F.binary_cross_entropy_with_logits(head(Xtr_t[idx]), Ytr_t[idx])
            opt.zero_grad()
            loss.backward()
            opt.step()
            tl += loss.item() * len(idx)
        head.eval()
        with torch.no_grad():
            vl = F.binary_cross_entropy_with_logits(head(Xva_t), Yva_t).item()
        hist.append({"epoch": ep, "train_loss": tl / len(perm), "val_loss": vl})
        if ep == 1 or ep % 5 == 0:
            log(f"  seed {seed} epoch {ep:>3}  train loss {tl / len(perm):.4f} | val loss {vl:.4f}")
        if vl < best - 1e-5:
            best, best_ep, best_state, bad = vl, ep, copy.deepcopy(head.state_dict()), 0
        else:
            bad += 1
            if bad >= patience:
                log(f"  seed {seed}: 조기 종료 (검증 손실이 {patience}에폭 동안 좋아지지 않음, best epoch {best_ep})")
                break
    head.load_state_dict(best_state)
    return head, hist, best_ep, best


# ───────────────────────── 미세조정 모드 ─────────────────────────
def finetune(encoder: HFEncoder, tr_texts, Ytr, va_texts, Yva, args, device, log):
    set_seed(args.seed)
    model = EmotionModel(encoder, args.hidden).to(device)
    opt = torch.optim.AdamW([
        {"params": model.head.parameters(), "lr": 1e-3, "weight_decay": 1e-2},
        {"params": model.encoder.parameters(), "lr": args.enc_lr, "weight_decay": 1e-2},
    ])
    steps_per_epoch = math.ceil(len(tr_texts) / args.bs)
    total = steps_per_epoch * args.epochs
    warm = max(1.0, total * 0.1)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warm) * max(0.0, 1 - s / total))
    Ytr_t = torch.tensor(Ytr, device=device)
    gen = torch.Generator().manual_seed(args.seed)
    best, best_ep, best_state, hist = float("inf"), 0, None, []
    clock, step = Clock(), 0
    for ep in range(1, args.epochs + 1):
        model.train()
        perm = torch.randperm(len(tr_texts), generator=gen).tolist()
        tl = 0.0
        for i in range(0, len(perm), args.bs):
            idx = perm[i:i + args.bs]
            loss = F.binary_cross_entropy_with_logits(model([tr_texts[j] for j in idx]), Ytr_t[idx])
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            tl += loss.item() * len(idx)
            if step % 50 == 0 or step == total:
                log(f"  epoch {ep}/{args.epochs}  step {step}/{total}  loss {loss.item():.4f}  (남은 시간 약 {clock.eta(step, total)})")
        Pv = predict_probs(model, va_texts)
        vl = bce_from_probs(Pv, Yva)
        hist.append({"epoch": ep, "train_loss": tl / len(perm), "val_loss": vl})
        log(f"  epoch {ep}: train loss {tl / len(perm):.4f} | val loss {vl:.4f}")
        if vl < best - 1e-5:
            best, best_ep = vl, ep
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
    model.load_state_dict(best_state)
    return model, hist, best_ep, best


# ───────────────────────── 명령 게이트 ─────────────────────────
GATE_CHAT_WEIGHT = 4.0      # 감정을 털어놓는 대화 예시는 적으니 더 무겁게 본다
GATE_KOTE_NEG = 1500        # '명령 아님' 예시로 쓰는 KOTE 감정 문장 수(학습·검증 각각 최대)
GATE_MAX_FALSE_KOTE = 0.01  # KOTE 감정 문장 중 명령으로 잘못 걸러지는 비율의 상한(검증셋)
GATE_MIN_THRESHOLD = 0.6


def fit_gate(pos_tr, chat_tr, kote_tr, pos_va, chat_va, kote_va, log):
    """명령(1) vs 감정 대화·KOTE 감정 문장(0) 로지스틱 회귀. 기준은 '감정 문장을 명령으로 오해하는 일이 거의 없는' 선에서
    명령을 가장 많이 잡는 값으로 검증셋에서 정한다(감정을 놓치는 것이 명령을 놓치는 것보다 나쁘다)."""
    X = np.vstack([pos_tr, chat_tr, kote_tr]) if len(chat_tr) else np.vstack([pos_tr, kote_tr])
    y = np.array([1] * len(pos_tr) + [0] * (len(chat_tr) + len(kote_tr)))
    w = np.array([1.0] * len(pos_tr) + [GATE_CHAT_WEIGHT] * len(chat_tr) + [1.0] * len(kote_tr))
    clf = LogisticRegression(C=4.0, max_iter=3000)
    clf.fit(X, y, sample_weight=w)
    gate = Gate(clf.coef_[0], clf.intercept_[0], 0.95)
    pv, pk = gate.prob(pos_va), gate.prob(kote_va)
    pc = gate.prob(chat_va) if len(chat_va) else np.zeros(0)
    best, feasible = (-1.0, 0.0), False
    for thr in np.arange(GATE_MIN_THRESHOLD, 0.996, 0.005):
        fk = float((pk >= thr).mean()) if len(pk) else 0.0
        fc = float((pc >= thr).mean()) if len(pc) else 0.0
        if fk <= GATE_MAX_FALSE_KOTE and fc == 0.0:
            rec = float((pv >= thr).mean())
            if rec >= best[0]:
                best, feasible = (rec, float(thr)), True
    thr = round(best[1], 3) if feasible else 0.97
    gate.threshold = thr
    yv = np.array([1] * len(pv) + [0] * (len(pc) + len(pk)))
    auc = float(roc_auc_score(yv, np.concatenate([pv, pc, pk]))) if len(set(yv)) == 2 else float("nan")
    rep_ = {"threshold": thr, "feasible": feasible, "auc": round(auc, 4),
            "command_recall_val": round(float((pv >= thr).mean()), 3), "n_command_val": int(len(pv)),
            "false_chat_val": None if not len(pc) else round(float((pc >= thr).mean()), 3), "n_chat_val": int(len(pc)),
            "false_kote_val": round(float((pk >= thr).mean()), 4) if len(pk) else None, "n_kote_val": int(len(pk)),
            "n_train": {"command": int(len(pos_tr)), "chat": int(len(chat_tr)), "kote": int(len(kote_tr))}}
    log(f"게이트: 기준 {thr} ({'조건 만족' if feasible else '조건을 만족하는 값이 없어 0.97로 고정 — 결과를 꼭 확인하세요'}) | "
        f"검증 명령 재현율 {rep_['command_recall_val']:.3f} | 감정 대화 오해 {rep_['false_chat_val']} | KOTE 감정 오해 {rep_['false_kote_val']} | AUC {auc:.3f}")
    gate.info = rep_
    return gate, rep_


# ───────────────────────── 최종 평가 보호 ─────────────────────────
def final_guard(config: str, version: str, reopen: bool) -> None:
    """테스트셋은 같은 설정(모드·인코더·학습 문장 수)당 한 번만 연다. 다시 학습해서 점수를 고르는 것을 막기 위함."""
    RESULTS.mkdir(parents=True, exist_ok=True)
    used = FINAL_LOG.read_text(encoding="utf-8") if FINAL_LOG.exists() else ""
    seen = f"\t{config}\t" in used
    if seen and not reopen:
        sys.exit(f"이 설정({config})은 이미 테스트셋으로 평가했습니다. 테스트셋은 설정당 한 번만 엽니다. "
                 "정말 다시 열려면 --reopen (기록에 REOPEN 표시)")
    with open(FINAL_LOG, "a", encoding="utf-8") as f:
        f.write(f"{datetime.now():%Y-%m-%d %H:%M}\t{config}\t{version}\t{'REOPEN' if seen else 'FIRST'}\n")


# ───────────────────────── 보고서 ─────────────────────────
def write_report(tag: str, summary: dict, history, check, test) -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"emotion_{tag}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    with open(RESULTS / f"emotion_{tag}_history.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(history[0].keys()))
        w.writeheader()
        w.writerows(history)
    v = summary["val"]
    lines = [
        f"# 감정 분류기 결과 — {summary['model_version']}", "",
        f"- 모드: {summary['mode']} / 인코더: {summary['encoder_name']} / 학습 문장 {summary['n_train']:,}개 / best epoch {summary['best_epoch']}",
        f"- 데이터: KOTE (MIT 라이선스, 온라인 댓글 44개 정서 multi-label). 검증셋 {summary['n_val']:,}문장", "",
        "## 검증셋 (KOTE val)", "",
        "| 지표 | 값 |", "|---|---|",
        f"| micro-F1 (임계값 {v['threshold']}) | {v['micro_f1']:.3f} |",
        f"| macro-F1 | {v['macro_f1']:.3f} |",
        f"| top-1 적중률 (가장 높은 정서가 정답 집합에 포함) | {v['top1_hit']:.3f} |",
        f"| 긍정/부정 방향 정확도 (뚜렷한 감정 {v['polarity_n']:,}문장) | {v['polarity_acc']:.3f} |",
        f"| 중립 판정 기준(가장 높은 정서 확률이 이 값 미만이면 중립) | {summary['neutral_below']} (Youden J {summary['neutral_youden_j']}, 감정 없는 검증 문장 {summary['n_val_neutral']}개) |", "",
        "KOTE 논문의 기준 모델(KcELECTRA)은 평균 F1 0.56 수준이다. 이 값과 비교할 때는 임계값·정의가 다를 수 있음에 주의한다.", "",
    ]
    ex, ne, gt = summary.get("extra"), summary.get("neutral_eval"), summary.get("gate")
    if ex:
        lines += ["## 감정 없는 명령 문장 처리 (추가 학습 데이터 + 명령 게이트)", "",
                  f"- 추가 데이터: Lumi 의도 분류기 데이터의 명령 문장 {ex['command_train']}개를 '감정 없음'으로 {ex['repeat']}번 반복 학습, "
                  f"검증용 {ex['command_val']}개는 조기 종료·중립 기준 선택에만 사용. AI가 쓴 문장이며 Lumi 점검 문장과 같은 {ex['dropped_check']}개는 제외했다.",
                  "- 위 KOTE 검증 지표는 KOTE 검증 문장만으로 계산해 추가 데이터 이전 결과와 비교할 수 있다.", ""]
        if ne:
            lines += ["| 검증 지표 | 값 |", "|---|---|",
                      f"| 명령 검증 문장 중 중립 처리 (모델만) | {ne.get('command_neutral_head_only', float('nan')):.3f} ({ne.get('n_command_val', 0)}문장) |"]
            if "command_neutral_with_gate" in ne:
                lines.append(f"| 명령 검증 문장 중 중립 처리 (모델 + 게이트) | {ne['command_neutral_with_gate']:.3f} |")
            lines += [f"| KOTE 감정 문장이 중립으로 잘못 나온 비율 (낮을수록 좋음) | {ne['false_neutral_kote_emotional']} ({ne['n_kote_emotional']}문장) |", ""]
        if gt:
            lines += ["명령 게이트(명령·요청이면 중립 처리):", "",
                      "| 항목 | 값 |", "|---|---|",
                      f"| 기준(확률 이상이면 명령) | {gt['threshold']}{'' if gt['feasible'] else ' (조건을 만족하는 값이 없어 고정한 값 — 주의)'} |",
                      f"| 검증 명령 문장 재현율 | {gt['command_recall_val']} ({gt['n_command_val']}문장) |",
                      f"| 감정 대화 검증 문장을 명령으로 오해한 비율 | {gt['false_chat_val']} ({gt['n_chat_val']}문장) |",
                      f"| KOTE 감정 검증 문장을 명령으로 오해한 비율 | {gt['false_kote_val']} ({gt['n_kote_val']}문장) |",
                      f"| 구분 능력(AUC) | {gt['auc']} |", ""]
    if test:
        lines += ["## 테스트셋 (KOTE test, 한 번만 평가)", "", "| 지표 | 값 |", "|---|---|",
                  f"| micro-F1 | {test['micro_f1']:.3f} |", f"| macro-F1 | {test['macro_f1']:.3f} |",
                  f"| top-1 적중률 | {test['top1_hit']:.3f} |", f"| 긍정/부정 방향 정확도 | {test['polarity_acc']:.3f} |", ""]
    if check:
        lines += [
            "## Lumi 대화체 점검 문장 (참고용 — 튜닝에 사용하지 않음)", "",
            f"AI가 작성한 {check['n']}문장이다. KOTE는 온라인 댓글이라 Lumi 대화와 말투가 다르므로, 이 수치로 도메인 차이를 가늠한다.", "",
            "| 구분 | 문장 수 | 긍정/부정 방향 | 기분(mood) 일치 |", "|---|---|---|---|",
            f"| 전체 | {check['n']} | {check['polarity_acc']:.3f} | {check['mood_acc']:.3f} |",
        ]
        for lang, d in sorted(check["by_lang"].items()):
            lines.append(f"| {lang} | {d['n']} | {d['polarity_acc']:.3f} | {d['mood_acc']:.3f} |")
        names = {"pos": "긍정 감정", "neg": "부정 감정", "neu": "감정 없음(명령·질문)"}
        lines += ["", "정답 종류별:", "", "| 정답 | 문장 수 | 방향 일치 | 기분 일치 | 중립으로 예측 |", "|---|---|---|---|---|"]
        for k in ("pos", "neg", "neu"):
            d = check["by_expected"].get(k)
            if d:
                lines.append(f"| {names[k]} | {d['n']} | {d['polarity_acc']:.3f} | {d['mood_acc']:.3f} | {d['pred_neutral']:.3f} |")
        if summary.get("extra"):
            lines += ["", "※ 점검 문장 중 '감정 없음(명령·질문)'은 추가 학습 데이터와 같은 방식으로 AI가 쓴 문장이라 이 칸은 낙관적으로 나올 수 있다. "
                      "긍정·부정 감정 문장은 학습에 쓰지 않은 말투이므로 이쪽이 실제 일반화 정도에 더 가깝다. 새 문장(급우 수집 등)으로 따로 확인해야 한다."]
        lines += ["", "### 틀린 문장", "", "| 문장 | 정답 | 예측 | 확신도 |", "|---|---|---|---|"]
        for it in check["failures"]:
            lines.append(f"| {it['text']} | {it['expected_polarity']} / {it['expected_moods']} | "
                         f"{it['pred_primary']} ({it['pred_mood']}, v={it['pred_valence']:+.2f}) | {it['confidence']:.2f} |")
    (RESULTS / f"emotion_{tag}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


# ───────────────────────── main ─────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=["head", "finetune"], default="head")
    ap.add_argument("--encoder", default=DEFAULT_ENCODER)
    ap.add_argument("--prefix", default=DEFAULT_PREFIX)
    ap.add_argument("--max-len", type=int, default=DEFAULT_MAX_LEN)
    ap.add_argument("--hidden", type=int, default=256)
    ap.add_argument("--seeds", type=int, default=3, help="head 모드에서 시드 개수")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--epochs", type=int, default=0, help="기본: head 60(조기 종료), finetune 3")
    ap.add_argument("--patience", type=int, default=6)
    ap.add_argument("--bs", type=int, default=0, help="기본: head 256, finetune 32")
    ap.add_argument("--enc-lr", type=float, default=3e-5, help="finetune 인코더 학습률")
    ap.add_argument("--max-train", type=int, default=0, help="학습 문장 수 제한(0=전체). finetune을 CPU에서 돌릴 때 사용")
    ap.add_argument("--limit", type=int, default=0, help="모든 분할을 앞 N개로 제한(동작 확인용)")
    ap.add_argument("--out", default=str(MODELS))
    ap.add_argument("--final", action="store_true", help="테스트셋으로 최종 평가(모델당 한 번만)")
    ap.add_argument("--reopen", action="store_true")
    ap.add_argument("--cpu", action="store_true")
    ap.add_argument("--no-extra", action="store_true", help="감정 없는 명령 문장(data/extra_*.csv)을 쓰지 않는다(예전 방식)")
    ap.add_argument("--extra-repeat", type=int, default=3, help="명령 문장을 학습에 몇 번 반복해 넣을지(KOTE 4만 개에 비해 적어서)")
    ap.add_argument("--no-gate", action="store_true", help="명령 게이트를 학습하지 않는다")
    args = ap.parse_args()
    args.epochs = args.epochs or (60 if args.mode == "head" else 3)
    args.bs = args.bs or (256 if args.mode == "head" else 32)
    device = "cpu" if args.cpu or not torch.cuda.is_available() else "cuda"
    log = lambda s: print(s, flush=True)  # noqa: E731

    log(f"장치: {device} | 모드: {args.mode} | 인코더: {args.encoder}")
    tr_texts, Ytr = load_split("train", args.limit)
    va_texts, Yva = load_split("val", args.limit)
    if args.max_train and args.max_train < len(tr_texts):
        rng = np.random.default_rng(args.seed)
        keep = np.sort(rng.choice(len(tr_texts), args.max_train, replace=False))
        tr_texts, Ytr = [tr_texts[i] for i in keep], Ytr[keep]
    # 감정 없는 명령 문장(의도 분류 데이터): KOTE에는 거의 없는 말투라 따로 더한다. KOTE 쪽 임베딩 캐시는 그대로 쓴다.
    extras = {} if args.no_extra else load_extras()
    cmd_tr, cmd_va = extras.get("command_train", []), extras.get("command_val", [])
    chat_tr, chat_va = extras.get("chat_train", []), extras.get("chat_val", [])
    rep_n = max(1, args.extra_repeat) if cmd_tr else 0
    n_kote_tr, n_kote_va = len(tr_texts), len(va_texts)
    tr_all = tr_texts + cmd_tr * rep_n
    va_all = va_texts + cmd_va
    Ytr_all = np.vstack([Ytr] + ([none_rows(len(cmd_tr) * rep_n)] if cmd_tr else []))
    Yva_all = np.vstack([Yva] + ([none_rows(len(cmd_va))] if cmd_va else []))
    log(f"학습 {n_kote_tr:,} (KOTE) + {len(cmd_tr) * rep_n:,} (명령 {len(cmd_tr)}문장 x {rep_n}) / "
        f"검증 {n_kote_va:,} (KOTE) + {len(cmd_va):,} (명령)")
    if extras.get("dropped_check"):
        log(f"점검 문장과 같아서 추가 데이터에서 뺀 문장: {extras['dropped_check']}개")

    encoder = HFEncoder(args.encoder, args.prefix, args.max_len).to(device)
    t0 = time.time()
    Xtr_k = Xva_k = None
    if args.mode == "head":
        Xtr_k = embed_split(encoder, tr_texts, "train", device)
        Xva_k = embed_split(encoder, va_texts, "val", device)
        Xc_tr = embed_split(encoder, cmd_tr, "xcmd_train", device) if cmd_tr else np.zeros((0, encoder.dim), "float32")
        Xc_va = embed_split(encoder, cmd_va, "xcmd_val", device) if cmd_va else np.zeros((0, encoder.dim), "float32")
        Xtr = np.vstack([Xtr_k] + [Xc_tr] * rep_n) if cmd_tr else Xtr_k
        Xva = np.vstack([Xva_k, Xc_va]) if cmd_va else Xva_k
        runs = []
        for s in range(args.seed, args.seed + args.seeds):
            head, hist, best_ep, best_vl = train_head(Xtr, Ytr_all, Xva, Yva_all, s, args.hidden, args.epochs, args.patience,
                                                      args.bs, 2e-3, device, log)
            runs.append((best_vl, s, head, hist, best_ep))
        vls = [r[0] for r in runs]
        log(f"시드별 검증 손실: {', '.join(f'{v:.4f}' for v in vls)} (평균 {np.mean(vls):.4f} ± {np.std(vls):.4f})")
        best_vl, best_seed, head, hist, best_ep = min(runs, key=lambda r: r[0])
        model = EmotionModel(encoder, args.hidden).to(device)
        model.head.load_state_dict(head.state_dict())
        with torch.no_grad():
            Pv = torch.sigmoid(model.head(torch.tensor(Xva, device=device))).cpu().numpy()
    else:
        model, hist, best_ep, best_vl = finetune(encoder, tr_all, Ytr_all, va_all, Yva_all, args, device, log)
        Pv = predict_probs(model, va_all)
    model.eval()

    Pv_k = Pv[:n_kote_va]
    thr = best_threshold(Pv_k, Yva)                                   # 정서 확률 임계값: KOTE 검증 문장으로만
    neutral_below, neutral_j, n_neutral = fit_neutral_threshold(Pv, Yva_all)  # 중립 기준: 명령 검증 문장까지 합쳐서
    val = evaluate_probs(Pv_k, Yva, thr, neutral_below)

    # 명령 게이트: 같은 인코더 임베딩 위의 작은 로지스틱 회귀
    gate, gate_rep = None, None
    if cmd_tr and not args.no_gate:
        def emb(texts, tag):
            if args.mode == "head":
                return embed_split(encoder, texts, tag, device)
            return embed_plain(model.encoder, texts)

        rng = np.random.default_rng(args.seed + 1)

        def kote_neg(Y, X, texts, tag):
            emo = np.flatnonzero(np.where(np.arange(N_LABELS) == NONE_IDX, 0, Y).sum(1) > 0)
            idx = np.sort(rng.choice(emo, min(GATE_KOTE_NEG, len(emo)), replace=False))
            return idx, (X[idx] if X is not None else embed_plain(model.encoder, [texts[i] for i in idx]))

        _, Ek_tr = kote_neg(Ytr, Xtr_k, tr_texts, "gate_k_tr")
        _, Ek_va = kote_neg(Yva, Xva_k, va_texts, "gate_k_va")
        E_cmd_tr = Xc_tr if args.mode == "head" else emb(cmd_tr, "")
        E_cmd_va = Xc_va if args.mode == "head" else emb(cmd_va, "")
        E_chat_tr = emb(chat_tr, "xchat_train") if chat_tr else np.zeros((0, encoder.dim), "float32")
        E_chat_va = emb(chat_va, "xchat_val") if chat_va else np.zeros((0, encoder.dim), "float32")
        gate, gate_rep = fit_gate(E_cmd_tr, E_chat_tr, Ek_tr, E_cmd_va, E_chat_va, Ek_va, log)

    # 명령 문장·감정 문장 처리 결과(검증셋)
    emo_k = np.where(np.arange(N_LABELS) == NONE_IDX, 0, Yva).sum(1) > 0
    neutral_k = np.array([summarize(p, neutral_below)["mood"] == "neutral" for p in Pv_k])
    neutral_eval = {"false_neutral_kote_emotional": round(float(neutral_k[emo_k].mean()), 3) if emo_k.any() else None,
                    "n_kote_emotional": int(emo_k.sum())}
    if cmd_va:
        Pc = Pv[n_kote_va:]
        c_head = np.array([summarize(p, neutral_below)["mood"] == "neutral" for p in Pc])
        neutral_eval.update(command_neutral_head_only=round(float(c_head.mean()), 3), n_command_val=len(cmd_va))
        if gate is not None:
            c_gate = gate.prob(E_cmd_va) >= gate.threshold
            neutral_eval["command_neutral_with_gate"] = round(float((c_head | c_gate).mean()), 3)
    log(f"명령 검증 문장 중립 처리율: {neutral_eval.get('command_neutral_head_only')} (게이트 포함 {neutral_eval.get('command_neutral_with_gate')}) | "
        f"KOTE 감정 문장이 중립으로 잘못 나온 비율: {neutral_eval['false_neutral_kote_emotional']}")

    check = eval_check(model, neutral_below, gate)
    version = f"kote-{args.mode}-{Path(args.encoder).name}-{datetime.now():%Y%m%d-%H%M}"
    extra_info = None if not cmd_tr else {"command_train": len(cmd_tr), "repeat": rep_n, "command_val": len(cmd_va),
                                          "chat_train": len(chat_tr), "chat_val": len(chat_va),
                                          "dropped_check": extras.get("dropped_check", 0)}
    meta = {"model_version": version, "mode": args.mode, "encoder_name": args.encoder,
            "trained_on": "KOTE (MIT)" + (" + Lumi 명령 문장(AI 작성, 의도 분류기 데이터)" if cmd_tr else ""),
            "n_train": len(tr_all), "best_epoch": best_ep, "val": val, "neutral_below": neutral_below,
            "neutral_youden_j": neutral_j, "n_val_neutral": n_neutral, "check_polarity_acc": check and check["polarity_acc"],
            "extra": extra_info, "gate": gate_rep, "neutral_eval": neutral_eval}
    save_model(Path(args.out), model, meta, save_encoder=args.mode == "finetune")
    if gate is not None:
        gate.save(Path(args.out))
    else:
        Gate.remove(Path(args.out))

    test = None
    if args.final:
        final_guard(f"{args.mode}|{args.encoder}|{len(tr_all)}", version, args.reopen)
        te_texts, Yte = load_split("test", args.limit)
        test = evaluate_probs(predict_probs(model, te_texts), Yte, thr, neutral_below)  # 임계값은 검증셋에서 정한 값을 그대로 사용
    summary = {**meta, "n_val": n_kote_va, "check": None if check is None else {k: v for k, v in check.items() if k != "failures"},
               "test": test, "train_seconds": round(time.time() - t0)}
    write_report(args.mode, summary, hist, check, test)

    log("\n===== 결과 =====")
    log(f"검증셋  micro-F1 {val['micro_f1']:.3f} | macro-F1 {val['macro_f1']:.3f} | top-1 적중 {val['top1_hit']:.3f} | 방향 정확도 {val['polarity_acc']:.3f}")
    if check:
        log(f"Lumi 점검 문장 {check['n']}개  방향 {check['polarity_acc']:.3f} | 기분 일치 {check['mood_acc']:.3f}")
    if test:
        log(f"테스트셋 micro-F1 {test['micro_f1']:.3f} | macro-F1 {test['macro_f1']:.3f} | 방향 정확도 {test['polarity_acc']:.3f}")
    if gate is not None:
        log(f"게이트 저장: {Path(args.out) / Gate.FILE} (기준 {gate.threshold})")
    log(f"저장: {args.out}  |  보고서: results/emotion_{args.mode}.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
