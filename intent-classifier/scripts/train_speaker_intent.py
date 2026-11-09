"""상대방 발화 의도 분류기 학습 — 기존 intent-classifier 파이프라인(common.py · evalkit.py)을 그대로 쓴다.

  python train_speaker_intent.py            # 학습·검증, models/speaker_intent/head.json 저장 (CPU 1~2분)
  python train_speaker_intent.py --final    # 테스트셋 최종 평가 (같은 설정당 한 번만, --reopen 이면 기록에 REOPEN)
  python train_speaker_intent.py --relation none   # 관계를 글에 붙이지 않는 비교 실험

기존 분류기(train_nn.py, 9개 라벨)는 '사용자가 Lumi에게 하는 명령'을 가르고, 이 분류기는 '다른 사람이 사용자에게 한 말'의 의도 12종을 가른다.
과제가 달라 모델은 따로 두고, 인코더(multilingual-e5-small, 고정)·접두어('query: ')·평가 도구는 같은 것을 쓴다.
- 분할: 비슷한 문장(자카드 ≥ 0.6, 같은 라벨)은 한 덩어리로 묶어 같은 쪽에만 넣고(누수 방지), 라벨별 60/20/20, seed 42.
  테스트는 처음 한 번 data/splits/speaker_test_frozen.csv 로 동결해 이후 데이터가 늘어도 바뀌지 않는다(동결 테스트와 ≥0.6 비슷한 새 문장은 학습 제외).
- 분류기: 소프트맥스 회귀(문장 약 360개라 MLP보다 단순한 모델) — 규제 강도 C는 검증 macro-F1로 고른다. 기준선: 글자 n-gram TF-IDF + 회귀.
- '애매함' 기준 tau: 검증셋에서 답한 문장 정확도 ≥ 0.85, 답하는 비율 ≥ 0.5 가 되는 가장 작은 값. 1위 확률이 tau 미만이면 Lumi는 하나로 단정하지 않는다.
결과: models/speaker_intent/head.json, results/speaker_intent.md·.json, results/preds_speaker_{val,test}.csv
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
from datetime import datetime

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import confusion_matrix
from sklearn.pipeline import make_pipeline

from common import DATA, RESULTS, ROOT, SPLITS, grams, jaccard, read_rows, write_rows
from evalkit import accuracy, bootstrap_ci, dump_preds, final_guard, log_final, macro_f1, report_md
from speaker_intent_labels import LABELS, N_LABELS, model_text

SEED = 42
GROUP_SIM = 0.6
ENCODER_NAME = "intfloat/multilingual-e5-small"
ENC_PREFIX = "query: "
ENC_MAX_LEN = 64            # 서버(serve.py)가 감정 모델 인코더로 같은 문장을 임베딩할 때의 최대 길이와 맞춘다
C_GRID = [0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0]
FIELDS = ("id", "text", "label", "relation", "lang", "hard", "source", "version")
SRC = DATA / "speaker_intent.csv"
FROZEN = SPLITS / "speaker_test_frozen.csv"
OUT = ROOT / "models" / "speaker_intent"
TAG = "speaker-intent"      # evalkit.final_guard 에 쓰는 평가 이름(기존 9라벨 평가와 구분)


# ───────────────────────── 데이터·분할 ─────────────────────────
def load_rows():
    rows = read_rows(SRC)
    bad = [r for r in rows if r.get("label") not in LABELS or not r.get("text")]
    if bad:
        sys.exit(f"라벨이 없거나 알 수 없는 줄 {len(bad)}개: {bad[:2]}")
    ids = [r["id"] for r in rows]
    if len(set(ids)) != len(ids):
        sys.exit("id가 중복됩니다")
    return rows


def groups(rows):
    parent = list(range(len(rows)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    g = [grams(r["text"]) for r in rows]
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            if jaccard(g[i], g[j]) >= GROUP_SIM:
                parent[find(i)] = find(j)
    out = {}
    for i, r in enumerate(rows):
        out.setdefault(find(i), []).append(r)
    return list(out.values())


def split_rows(rows):
    """동결 테스트가 있으면 그대로 쓰고, 없으면 만든다. 나머지는 라벨별로 학습:검증 = 3:1."""
    if FROZEN.exists():
        test = read_rows(FROZEN)
        tids = {r["id"] for r in test}
        tg = [grams(r["text"]) for r in test]
        rest, leaked = [], 0
        for r in rows:
            if r["id"] in tids:
                continue
            g = grams(r["text"])
            if any(jaccard(g, x) >= GROUP_SIM for x in tg):
                leaked += 1
            else:
                rest.append(r)
        train, val = _tv(rest)
        return {"train": train, "val": val, "test": test}, leaked
    rng = random.Random(SEED)
    parts = {"train": [], "val": [], "test": []}
    for lab in LABELS:
        gs = groups([r for r in rows if r["label"] == lab])
        rng.shuffle(gs)
        n = sum(len(g) for g in gs)
        want_t, want_v = round(n * 0.2), round(n * 0.2)
        for name, want in (("test", want_t), ("val", want_v)):
            got = 0
            for g in list(gs):
                if got < want and got + len(g) <= want + (1 if len(g) > 1 else 0):
                    parts[name].extend(g)
                    got += len(g)
                    gs.remove(g)
        for g in gs:
            parts["train"].extend(g)
    for k in parts:
        parts[k].sort(key=lambda r: r["id"])
    write_rows(FROZEN, parts["test"], FIELDS)   # 한 번 만든 테스트셋은 이후 바꾸지 않는다
    return parts, 0


def _tv(rows):
    rng = random.Random(SEED)
    tr, va = [], []
    for lab in LABELS:
        gs = groups([r for r in rows if r["label"] == lab])
        rng.shuffle(gs)
        want = round(sum(len(g) for g in gs) * 0.25)
        got = 0
        for g in gs:
            if got < want and got + len(g) <= want + (1 if len(g) > 1 else 0):
                va.extend(g)
                got += len(g)
            else:
                tr.extend(g)
    return sorted(tr, key=lambda r: r["id"]), sorted(va, key=lambda r: r["id"])


# ───────────────────────── 임베딩 ─────────────────────────
def build_embedder():
    """기존 train_nn.py 의 HFEncoder·임베딩 캐시를 그대로 쓴다(고정 인코더)."""
    import torch
    from train_nn import HFEncoder, embed_rows  # torch/transformers 필요

    enc = HFEncoder(ENCODER_NAME, ENC_PREFIX, ENC_MAX_LEN).eval()

    def embed(texts):
        with torch.no_grad():
            return embed_rows(enc, [{"text": t} for t in texts], ENCODER_NAME, ENC_PREFIX).numpy()

    return embed, {"encoder_name": ENCODER_NAME, "prefix": ENC_PREFIX, "max_len": ENC_MAX_LEN}


# ───────────────────────── 학습 보조 ─────────────────────────
def fit_lr(X, y, C):
    return LogisticRegression(C=C, max_iter=5000).fit(X, y)


def proba_full(clf, X):
    out = np.zeros((len(X), N_LABELS))
    out[:, clf.classes_] = clf.predict_proba(X)
    return out


def softmax(z):
    z = z - z.max(1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(1, keepdims=True)


def fit_temperature(logits, y):
    """검증셋 로그손실이 가장 낮은 온도 T. 적은 데이터로 학습한 회귀는 확률이 납작해(1위가 0.3~0.4) 'uncertain'이 너무 많아지므로 확률을 보정한다.
    순위(어느 의도가 1위인지)는 바뀌지 않는다."""
    best = (float("inf"), 1.0)
    for T in np.arange(0.1, 3.01, 0.05):
        p = softmax(logits / T)
        nll = -float(np.mean(np.log(p[np.arange(len(y)), y] + 1e-12)))
        if nll < best[0]:
            best = (nll, float(T))
    return round(best[1], 2)


def pick_tau(y, P, min_cov=0.5, target=0.80):
    """애매함 기준: 답한 문장 정확도 ≥ target 이면서 답하는 비율 ≥ min_cov 인 가장 작은 tau.
    그런 값이 없으면 답하는 비율이 약 60%가 되는 값(답한 문장의 실제 정확도는 보고서에 그대로 적는다)."""
    top, pred = P.max(1), P.argmax(1)
    for tau in np.arange(0.20, 0.91, 0.01):
        ans = top >= tau
        if ans.mean() < min_cov:
            break
        if ans.any() and (pred[ans] == y[ans]).mean() >= target:
            return round(float(tau), 2)
    return round(float(np.quantile(top, 0.4)), 2)


def answered(y, P, tau):
    top, pred = P.max(1), P.argmax(1)
    ans = top >= tau
    return {"tau": tau, "coverage": float(ans.mean()), "accuracy_answered": float((pred[ans] == y[ans]).mean()) if ans.any() else float("nan")}


def stats(y_idx, P):
    pred = [LABELS[i] for i in P.argmax(1)]
    yt = [LABELS[i] for i in y_idx]
    lo, hi = bootstrap_ci(yt, pred)
    top2 = float(np.mean([y_idx[i] in np.argsort(-P[i])[:2] for i in range(len(y_idx))]))
    return {"n": len(yt), "accuracy": accuracy(yt, pred), "acc_ci95": [round(lo, 3), round(hi, 3)], "macro_f1": macro_f1(yt, pred, LABELS), "top2": top2}, pred


# ───────────────────────── 실행 ─────────────────────────
def run(args, embed=None, enc_meta=None):
    rows = load_rows()
    parts, leaked = split_rows(rows)
    txt = {k: [model_text(r["relation"], r["text"], args.relation) for r in v] for k, v in parts.items()}
    y = {k: np.array([LABELS.index(r["label"]) for r in v]) for k, v in parts.items()}
    print(f"문장 {len(rows)}개 → 학습 {len(y['train'])} / 검증 {len(y['val'])} / 테스트 {len(y['test'])} (관계 표기: {args.relation})"
          + (f" · 동결 테스트와 비슷해 제외 {leaked}개" if leaked else ""), flush=True)
    if embed is None:
        embed, enc_meta = build_embedder()
    X = {k: embed(txt[k]) for k in ("train", "val")}

    best = None
    for C in C_GRID:
        P = proba_full(fit_lr(X["train"], y["train"], C), X["val"])
        s, _ = stats(y["val"], P)
        print(f"  C={C:<5} 검증 정확도 {s['accuracy']:.3f} | macro-F1 {s['macro_f1']:.3f}", flush=True)
        if best is None or s["macro_f1"] > best[0] + 1e-9:
            best = (s["macro_f1"], C, P)
    _, C, _ = best
    clf_tr = fit_lr(X["train"], y["train"], C)
    logit_v = clf_tr.decision_function(X["val"])
    T = fit_temperature(logit_v, y["val"])
    Pv = softmax(logit_v / T)
    print(f"  선택: C={C}, 확률 보정 온도 T={T}", flush=True)
    val, pred_v = stats(y["val"], Pv)
    tau = pick_tau(y["val"], Pv)
    cov = answered(y["val"], Pv, tau)

    base = make_pipeline(TfidfVectorizer(analyzer="char_wb", ngram_range=(1, 4), sublinear_tf=True), LogisticRegression(C=10, max_iter=5000))
    base.fit(txt["train"], y["train"])
    Pb = np.zeros((len(txt["val"]), N_LABELS))
    Pb[:, base.classes_] = base.predict_proba(txt["val"])
    base_val, _ = stats(y["val"], Pb)

    cm = confusion_matrix(y["val"], Pv.argmax(1), labels=list(range(N_LABELS)))
    per_class = {LABELS[i]: {"n": int(cm[i].sum()), "recall": round(float(cm[i, i] / max(1, cm[i].sum())), 3)} for i in range(N_LABELS)}
    conf = sorted(((int(cm[i, j]), LABELS[i], LABELS[j]) for i in range(N_LABELS) for j in range(N_LABELS) if i != j and cm[i, j]), reverse=True)[:8]

    RESULTS.mkdir(exist_ok=True)
    dump_preds(RESULTS / "preds_speaker_val.csv", parts["val"], pred_v, Pv)
    (RESULTS / "speaker_intent_val_breakdown.md").write_text(report_md(parts["val"], pred_v, "상대 발화 의도 검증셋 분해"), encoding="utf-8")

    # 배포용: 학습+검증으로 다시 맞춘다(데이터가 적어서). 테스트셋은 여기서도 쓰지 않는다.
    clf = fit_lr(np.vstack([X["train"], X["val"]]), np.concatenate([y["train"], y["val"]]), C)
    if len(clf.classes_) != N_LABELS:
        sys.exit("학습 데이터에 없는 의도가 있습니다.")
    version = f"speaker-intent-e5small-lr-{datetime.now():%Y%m%d-%H%M}"

    test = None
    if args.final:
        tag = f"{TAG}-{args.relation}"
        reopened = final_guard(tag, args.reopen)          # 같은 설정으로 또 열려 하면 여기서 멈춘다
        Pt = softmax(clf.decision_function(embed(txt["test"])) / T)
        ts, pred_t = stats(y["test"], Pt)
        test = {**ts, **answered(y["test"], Pt, tau)}
        dump_preds(RESULTS / "preds_speaker_test.csv", parts["test"], pred_t, Pt)
        (RESULTS / "speaker_intent_test_breakdown.md").write_text(report_md(parts["test"], pred_t, "상대 발화 의도 테스트셋 분해"), encoding="utf-8")
        log_final(tag, f"acc={ts['accuracy']:.3f} macroF1={ts['macro_f1']:.3f}", reopened)

    meta = {"model_version": version, "labels": LABELS, "relation_mode": args.relation, "C": C, "temperature": T, "tau": tau,
            "coef": clf.coef_.astype("float64").tolist(), "intercept": clf.intercept_.astype("float64").tolist(),
            "n_train": int(len(y["train"]) + len(y["val"])), **(enc_meta or {})}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "head.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

    summary = {"model_version": version, "relation_mode": args.relation, "C": C, "temperature": T, "tau": tau, "n": {k: len(v) for k, v in y.items()},
               "val": val, "val_answered": cov, "baseline_tfidf_val": base_val, "per_class_val": per_class,
               "top_confusions_val": [{"n": n, "true": a, "pred": b} for n, a, b in conf], "test": test}
    write_report(summary)
    print("\n===== 결과 =====")
    print(f"검증  정확도 {val['accuracy']:.3f} (95% 구간 {val['acc_ci95'][0]}~{val['acc_ci95'][1]}) | macro-F1 {val['macro_f1']:.3f} | 상위2 포함 {val['top2']:.3f}")
    print(f"기준선(TF-IDF) 검증 정확도 {base_val['accuracy']:.3f}")
    print(f"애매함 기준 tau={tau}: 답하는 비율 {cov['coverage']:.2f}, 그중 정확도 {cov['accuracy_answered']:.2f}")
    if test:
        print(f"테스트 정확도 {test['accuracy']:.3f} | macro-F1 {test['macro_f1']:.3f}")
    print(f"저장: {OUT / 'head.json'} | 보고서: {RESULTS / 'speaker_intent.md'}")
    return summary


def write_report(s):
    (RESULTS / "speaker_intent.json").write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")
    v, b, c = s["val"], s["baseline_tfidf_val"], s["val_answered"]
    L = [f"# 상대방 발화 의도 분류기 결과 — {s['model_version']}", "",
         f"- 인코더 multilingual-e5-small(고정) + 소프트맥스 회귀(C={s['C']}, 확률 보정 온도 T={s.get('temperature', 1.0)}) / 관계 표기: {s['relation_mode']} / 문장 {sum(s['n'].values())}개 "
         f"(학습 {s['n']['train']}, 검증 {s['n']['val']}, 테스트 {s['n']['test']})",
         "- 데이터는 AI가 쓴 문장이며 의도당 30개로 적다. 아래 수치는 '같은 방식으로 쓴 문장'에서의 값이라 실제 말투에서는 낮아질 수 있다.", "",
         "## 검증셋", "", "| 지표 | 값 |", "|---|---|",
         f"| 정확도 | {v['accuracy']:.3f} (95% 구간 {v['acc_ci95'][0]}~{v['acc_ci95'][1]}, {v['n']}문장) |",
         f"| macro-F1 | {v['macro_f1']:.3f} |", f"| 정답이 상위 2개 안에 있음 | {v['top2']:.3f} |",
         f"| 기준선(글자 n-gram TF-IDF + 회귀) 정확도 | {b['accuracy']:.3f} |", "",
         f"애매함 기준 tau={s['tau']}: 검증셋에서 답하는 비율 {c['coverage']:.2f}, 답한 문장의 정확도 {c['accuracy_answered']:.2f}.", "",
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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--relation", choices=["prefix", "none"], default="prefix")
    ap.add_argument("--final", action="store_true", help="테스트셋으로 최종 평가(설정당 한 번만)")
    ap.add_argument("--reopen", action="store_true")
    run(ap.parse_args())
    return 0


if __name__ == "__main__":
    sys.exit(main())
