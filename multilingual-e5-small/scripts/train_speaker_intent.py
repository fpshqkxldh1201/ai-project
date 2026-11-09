"""상대방 발화 의도 분류기 학습: 다국어 인코더(multilingual-e5-small, 고정) 임베딩 위에 12개 의도를 가르는 소프트맥스 회귀를 직접 학습한다.

  python scripts/train_speaker_intent.py                 # 학습·검증, models/speaker_intent/ 저장 (CPU로 1~2분)
  python scripts/train_speaker_intent.py --final         # 테스트셋 최종 평가 (같은 설정당 한 번만!)
  python scripts/train_speaker_intent.py --relation none # 관계를 글에 붙이지 않는 비교 실험

데이터: data/speaker_intent.csv (label, relation, text) — AI가 쓴 한국어 문장 360개(12 의도 x 30, 관계 6종 x 5). 실제 말투와 다를 수 있어
  급우에게 받은 실제 말투 문장으로 따로 평가해야 한다(README·결과 보고서의 한계 참고).
분할: 의도별로 문장 해시 순서대로 학습 60% / 검증 20% / 테스트 20%(고정 규칙이라 다시 돌려도 같은 분할).
  - 검증: 규제 강도(C)와 '애매함' 기준(tau) 선택. 테스트: --final 로 설정당 한 번.
  - 저장되는 모델은 검증 후 학습+검증 데이터로 다시 맞춘 것(데이터가 적어서). 테스트셋은 어느 단계에서도 쓰지 않는다.
기준선: 글자 n-gram TF-IDF + 로지스틱 회귀(같은 분할)와 나란히 보고한다.

결과: models/speaker_intent/head.json, results/speaker_intent.md·.json
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
from sklearn.pipeline import make_pipeline

from speaker_intent_labels import LABELS, N_LABELS, model_text

ROOT = Path(__file__).resolve().parent.parent
DATA = Path(os.environ.get("SPEAKER_INTENT_DATA", ROOT / "data" / "speaker_intent.csv"))
OUT = Path(os.environ.get("SPEAKER_INTENT_MODEL_DIR", ROOT / "models" / "speaker_intent"))
RESULTS = Path(os.environ.get("EMOTION_RESULTS_DIR", ROOT / "results"))
CACHE = Path(os.environ.get("EMOTION_CACHE_DIR", ROOT / "data" / "cache"))
FINAL_LOG = RESULTS / "speaker_intent_final_log.txt"
ENCODER_NAME = "intfloat/multilingual-e5-small"
C_GRID = [0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0]


# ───────────────────────── 데이터 ─────────────────────────
def read_rows(path: Path = DATA):
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = [{k: (v or "").strip() for k, v in r.items()} for r in csv.DictReader(f)]
    bad = [r for r in rows if r.get("label") not in LABELS or not r.get("text")]
    if bad:
        sys.exit(f"라벨이 없거나 알 수 없는 줄이 {len(bad)}개 있습니다: {bad[:2]}")
    seen = set()
    for r in rows:
        key = "".join(r["text"].split()).lower()
        if key in seen:
            sys.exit(f"같은 문장이 두 번 들어 있습니다(분할 누수 방지): {r['text']}")
        seen.add(key)
    return rows


def split_rows(rows, train=0.6, val=0.2):
    """의도별로 sha1(문장) 순서로 줄 세워 앞에서부터 학습/검증/테스트. 행이 늘어도 기존 문장의 순서는 변하지 않는다."""
    out = {"train": [], "val": [], "test": []}
    for lab in LABELS:
        grp = sorted((r for r in rows if r["label"] == lab), key=lambda r: hashlib.sha1(r["text"].encode("utf-8")).hexdigest())
        n = len(grp)
        a, b = round(n * train), round(n * (train + val))
        out["train"] += grp[:a]
        out["val"] += grp[a:b]
        out["test"] += grp[b:]
    return out


# ───────────────────────── 임베딩 ─────────────────────────
def build_embedder():
    """실제 인코더(multilingual-e5-small, 고정)로 문장 목록 → (N, D) 임베딩. 한 번 계산한 것은 data/cache에 저장한다."""
    import torch  # 이 스크립트의 나머지는 torch 없이 동작한다(시험용으로 embed_texts를 바꿔 끼울 수 있다)
    from emotion_model import DEFAULT_MAX_LEN, DEFAULT_PREFIX, HFEncoder

    enc = HFEncoder(ENCODER_NAME, DEFAULT_PREFIX, DEFAULT_MAX_LEN).eval()
    meta = {"encoder_name": ENCODER_NAME, "prefix": DEFAULT_PREFIX, "max_len": DEFAULT_MAX_LEN}

    @torch.no_grad()
    def embed(texts, batch_size=32):
        sig = hashlib.sha1("\n".join(texts).encode("utf-8")).hexdigest()[:12]
        path = CACHE / f"spk_{sig}_{len(texts)}.npy"
        if path.exists():
            return np.load(path)
        out = np.zeros((len(texts), enc.dim), dtype="float32")
        for i in range(0, len(texts), batch_size):
            out[i:i + batch_size] = enc.embed(texts[i:i + batch_size]).float().cpu().numpy()
        CACHE.mkdir(parents=True, exist_ok=True)
        np.save(path, out)
        return out

    return embed, meta


# ───────────────────────── 지표 ─────────────────────────
def wilson(k: int, n: int, z: float = 1.96):
    if n == 0:
        return (float("nan"), float("nan"))
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def metrics(y_true, proba):
    y_pred = proba.argmax(1)
    acc = float(accuracy_score(y_true, y_pred))
    lo, hi = wilson(int((y_true == y_pred).sum()), len(y_true))
    return {"n": int(len(y_true)), "accuracy": acc, "acc_ci95": [round(lo, 3), round(hi, 3)],
            "macro_f1": float(f1_score(y_true, y_pred, average="macro", zero_division=0)),
            "top2": float(np.mean([y_true[i] in np.argsort(-proba[i])[:2] for i in range(len(y_true))]))}


def pick_tau(y_true, proba, min_coverage=0.5, target_acc=0.85):
    """'애매함' 기준: 1위 확률이 tau 미만이면 하나로 단정하지 않고 상위 2개를 보여 준다.
    검증셋에서, 답한 문장의 정확도가 target_acc 이상이면서 답하는 비율(coverage)이 min_coverage 이상인 가장 작은 tau."""
    top, pred = proba.max(1), proba.argmax(1)
    for tau in np.arange(0.30, 0.91, 0.02):
        ans = top >= tau
        if ans.mean() < min_coverage:
            break
        if ans.any() and (pred[ans] == y_true[ans]).mean() >= target_acc:
            return round(float(tau), 2)
    return 0.45


def coverage_at(y_true, proba, tau):
    top, pred = proba.max(1), proba.argmax(1)
    ans = top >= tau
    return {"tau": tau, "coverage": float(ans.mean()), "accuracy_answered": float((pred[ans] == y_true[ans]).mean()) if ans.any() else float("nan")}


def final_guard(config: str, version: str, reopen: bool) -> None:
    """테스트셋은 같은 설정당 한 번만 연다(다시 학습해서 점수를 고르는 것을 막는다)."""
    RESULTS.mkdir(parents=True, exist_ok=True)
    used = FINAL_LOG.read_text(encoding="utf-8") if FINAL_LOG.exists() else ""
    seen = f"\t{config}\t" in used
    if seen and not reopen:
        sys.exit(f"이 설정({config})은 이미 테스트셋으로 평가했습니다. 설정당 한 번만 엽니다. 정말 다시 열려면 --reopen (기록에 REOPEN 표시)")
    with open(FINAL_LOG, "a", encoding="utf-8") as f:
        f.write(f"{datetime.now():%Y-%m-%d %H:%M}\t{config}\t{version}\t{'REOPEN' if seen else 'FIRST'}\n")


# ───────────────────────── 학습 ─────────────────────────
def fit_lr(X, y, C):
    return LogisticRegression(C=C, max_iter=5000).fit(X, y)


def proba_full(clf, X):
    """clf.classes_ 순서를 LABELS 순서(0..N-1)로 맞춘 (N, 12) 확률."""
    out = np.zeros((len(X), N_LABELS), dtype="float64")
    out[:, clf.classes_] = clf.predict_proba(X)
    return out


def run(args, embed=None, enc_meta=None):
    rows = read_rows()
    parts = split_rows(rows)
    texts = {k: [model_text(r["relation"], r["text"], args.relation) for r in v] for k, v in parts.items()}
    y = {k: np.array([LABELS.index(r["label"]) for r in v]) for k, v in parts.items()}
    print(f"문장 {len(rows)}개 → 학습 {len(y['train'])} / 검증 {len(y['val'])} / 테스트 {len(y['test'])} (관계 표기: {args.relation})", flush=True)

    if embed is None:
        embed, enc_meta = build_embedder()
    X = {k: embed(texts[k]) for k in ("train", "val")}

    # 1) 규제 강도 선택 (검증 macro-F1)
    best = None
    for C in C_GRID:
        P = proba_full(fit_lr(X["train"], y["train"], C), X["val"])
        m = metrics(y["val"], P)
        print(f"  C={C:<5} 검증 정확도 {m['accuracy']:.3f} | macro-F1 {m['macro_f1']:.3f}", flush=True)
        if best is None or m["macro_f1"] > best[0] + 1e-9:
            best = (m["macro_f1"], C, P, m)
    _, C, Pv, val = best
    tau = pick_tau(y["val"], Pv)
    cov = coverage_at(y["val"], Pv, tau)

    # 2) 기준선(같은 분할, 같은 입력)
    base = make_pipeline(TfidfVectorizer(analyzer="char_wb", ngram_range=(1, 4), sublinear_tf=True), LogisticRegression(C=10, max_iter=5000))
    base.fit(texts["train"], y["train"])
    Pb = np.zeros((len(texts["val"]), N_LABELS))
    Pb[:, base.classes_] = base.predict_proba(texts["val"])
    base_val = metrics(y["val"], Pb)

    # 3) 검증 혼동 행렬
    cm = confusion_matrix(y["val"], Pv.argmax(1), labels=list(range(N_LABELS)))
    per_class = {LABELS[i]: {"n": int(cm[i].sum()), "recall": round(float(cm[i, i] / max(1, cm[i].sum())), 3)} for i in range(N_LABELS)}
    confusions = sorted(((int(cm[i, j]), LABELS[i], LABELS[j]) for i in range(N_LABELS) for j in range(N_LABELS) if i != j and cm[i, j]), reverse=True)[:8]

    # 4) 배포용: 학습+검증으로 다시 맞춘다
    X_all = np.vstack([X["train"], X["val"]])
    y_all = np.concatenate([y["train"], y["val"]])
    clf = fit_lr(X_all, y_all, C)
    if len(clf.classes_) != N_LABELS:
        sys.exit("학습 데이터에 없는 의도가 있습니다.")
    version = f"speaker-intent-e5small-lr-{datetime.now():%Y%m%d-%H%M}"

    test = None
    if args.final:
        final_guard(f"{args.relation}|{ENCODER_NAME}|{len(y_all)}", version, args.reopen)
        Pt = proba_full(clf, embed(texts["test"]))
        test = {**metrics(y["test"], Pt), **coverage_at(y["test"], Pt, tau)}

    meta = {"model_version": version, "labels": LABELS, "relation_mode": args.relation, "C": C, "tau": tau,
            "coef": clf.coef_.astype("float64").tolist(), "intercept": clf.intercept_.astype("float64").tolist(),
            "n_train": int(len(y_all)), **(enc_meta or {})}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "head.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

    summary = {"model_version": version, "relation_mode": args.relation, "C": C, "tau": tau, "n": {k: len(v) for k, v in y.items()},
               "val": val, "val_answered": cov, "baseline_tfidf_val": base_val, "per_class_val": per_class,
               "top_confusions_val": [{"n": n, "true": a, "pred": b} for n, a, b in confusions], "test": test}
    write_report(summary)
    print("\n===== 결과 =====")
    print(f"검증  정확도 {val['accuracy']:.3f} (95% 구간 {val['acc_ci95'][0]}~{val['acc_ci95'][1]}) | macro-F1 {val['macro_f1']:.3f} | 상위2 포함 {val['top2']:.3f}")
    print(f"기준선(TF-IDF) 검증 정확도 {base_val['accuracy']:.3f}")
    print(f"애매함 기준 tau={tau}: 답하는 비율 {cov['coverage']:.2f}, 그중 정확도 {cov['accuracy_answered']:.2f}")
    if test:
        print(f"테스트 정확도 {test['accuracy']:.3f} | macro-F1 {test['macro_f1']:.3f}")
    print(f"저장: {OUT / 'head.json'} | 보고서: {RESULTS / 'speaker_intent.md'}")
    return summary


def write_report(s) -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "speaker_intent.json").write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")
    v, b, c = s["val"], s["baseline_tfidf_val"], s["val_answered"]
    L = [f"# 상대방 발화 의도 분류기 결과 — {s['model_version']}", "",
         f"- 인코더 multilingual-e5-small(고정) + 소프트맥스 회귀(C={s['C']}) / 관계 표기: {s['relation_mode']} / 문장 {sum(s['n'].values())}개 "
         f"(학습 {s['n']['train']}, 검증 {s['n']['val']}, 테스트 {s['n']['test']})",
         "- 데이터는 AI가 쓴 문장이며 의도당 30개로 적다. 아래 수치는 '같은 방식으로 쓴 문장'에서의 값이라 실제 말투에서는 낮아질 수 있다.", "",
         "## 검증셋", "", "| 지표 | 값 |", "|---|---|",
         f"| 정확도 | {v['accuracy']:.3f} (95% 구간 {v['acc_ci95'][0]}~{v['acc_ci95'][1]}, {v['n']}문장) |",
         f"| macro-F1 | {v['macro_f1']:.3f} |", f"| 정답이 상위 2개 안에 있음 | {v['top2']:.3f} |",
         f"| 기준선(글자 n-gram TF-IDF + 로지스틱 회귀) 정확도 | {b['accuracy']:.3f} |", "",
         f"애매함 기준 tau={s['tau']}: 1위 확률이 이보다 낮으면 하나로 단정하지 않고 상위 2개를 보여 준다. 검증셋에서 답하는 비율 {c['coverage']:.2f}, 답한 문장의 정확도 {c['accuracy_answered']:.2f}.", "",
         "### 의도별 재현율(검증)", "", "| 의도 | 문장 수 | 재현율 |", "|---|---|---|"]
    L += [f"| {k} | {d['n']} | {d['recall']:.2f} |" for k, d in s["per_class_val"].items()]
    L += ["", "### 자주 헷갈린 쌍(검증)", "", "| 정답 | 예측 | 횟수 |", "|---|---|---|"]
    L += [f"| {d['true']} | {d['pred']} | {d['n']} |" for d in s["top_confusions_val"]]
    if s["test"]:
        t = s["test"]
        L += ["", "## 테스트셋 (한 번만 평가)", "", "| 지표 | 값 |", "|---|---|",
              f"| 정확도 | {t['accuracy']:.3f} (95% 구간 {t['acc_ci95'][0]}~{t['acc_ci95'][1]}, {t['n']}문장) |", f"| macro-F1 | {t['macro_f1']:.3f} |",
              f"| 상위 2개 포함 | {t['top2']:.3f} |", f"| tau={t['tau']}에서 답하는 비율 / 그중 정확도 | {t['coverage']:.2f} / {t['accuracy_answered']:.2f} |"]
    (RESULTS / "speaker_intent.md").write_text("\n".join(L) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--relation", choices=["prefix", "none"], default="prefix", help="관계를 글 앞에 붙일지(기본 prefix)")
    ap.add_argument("--final", action="store_true", help="테스트셋으로 최종 평가(설정당 한 번만)")
    ap.add_argument("--reopen", action="store_true")
    run(ap.parse_args())
    return 0


if __name__ == "__main__":
    sys.exit(main())
