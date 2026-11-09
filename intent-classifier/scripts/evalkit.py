"""평가 도구 모음: 부트스트랩 95% 신뢰구간, 언어·출처·hard·클래스별 분해, 혼동 쌍, 예측 CSV 저장,
테스트셋 재평가 방지(final_guard).

CLI:  python evalkit.py results/preds_baseline_val.csv      → 같은 폴더에 .md 보고서를 만든다."""
import csv
import json
import sys
import time
from collections import Counter

import numpy as np

from common import LABELS, RESULTS, read_rows

META = RESULTS / "frozen_v1.json"
LOG = RESULTS / "final_log.txt"


# ───────────────────────── 지표 ─────────────────────────
def macro_f1(y_true, y_pred, labels=LABELS):
    f = []
    for lab in labels:
        tp = sum(t == lab and p == lab for t, p in zip(y_true, y_pred))
        fp = sum(t != lab and p == lab for t, p in zip(y_true, y_pred))
        fn = sum(t == lab and p != lab for t, p in zip(y_true, y_pred))
        f.append(0.0 if tp == 0 else 2 * tp / (2 * tp + fp + fn))
    return float(np.mean(f))


def accuracy(y_true, y_pred):
    return float(np.mean([t == p for t, p in zip(y_true, y_pred)])) if y_true else float("nan")


def bootstrap_ci(y_true, y_pred, metric=accuracy, n=2000, seed=0, alpha=0.05):
    """문장을 복원추출해 지표의 (하한, 상한)을 구한다. 표본이 작을수록 구간이 넓다는 걸 숫자로 보여 준다."""
    rng = np.random.default_rng(seed)
    yt, yp = np.array(y_true, dtype=object), np.array(y_pred, dtype=object)
    vals = []
    for _ in range(n):
        idx = rng.integers(0, len(yt), len(yt))
        vals.append(metric(list(yt[idx]), list(yp[idx])))
    return float(np.quantile(vals, alpha / 2)), float(np.quantile(vals, 1 - alpha / 2))


def breakdown(rows, pred, key):
    """key(예: lang, source, hard, label)별 정확도. [(값, 개수, 정확도)]"""
    out = {}
    for r, p in zip(rows, pred):
        out.setdefault(r.get(key, "?"), []).append(r["label"] == p)
    return [(k, len(v), float(np.mean(v))) for k, v in sorted(out.items())]


def confusion_pairs(rows, pred, top=8):
    c = Counter((r["label"], p) for r, p in zip(rows, pred) if r["label"] != p)
    return c.most_common(top)


def mcnemar(y_true, pred_a, pred_b):
    """두 모델이 같은 문장에서 엇갈린 횟수와 정확 이항검정 p값(양측). b: A만 맞음, c: B만 맞음"""
    from math import comb
    b = sum(t == a and t != p for t, a, p in zip(y_true, pred_a, pred_b))
    c = sum(t != a and t == p for t, a, p in zip(y_true, pred_a, pred_b))
    n = b + c
    if n == 0:
        return b, c, 1.0
    k = min(b, c)
    p = min(1.0, 2 * sum(comb(n, i) for i in range(k + 1)) / 2 ** n)
    return b, c, p


# ───────────────────────── 파일 입출력 ─────────────────────────
def dump_preds(path, rows, pred, probs=None):
    """문장별 예측을 CSV로 저장(id,text,label,pred,conf,lang,source,hard). 나중에 어떤 분석이든 다시 계산할 수 있다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["id", "text", "label", "pred", "conf", "lang", "source", "hard"])
        for i, (r, p) in enumerate(zip(rows, pred)):
            conf = "" if probs is None else f"{float(probs[i].max()):.4f}"
            w.writerow([r["id"], r["text"], r["label"], p, conf, r.get("lang", ""), r.get("source", ""), r.get("hard", "")])


def report_md(rows, pred, title):
    y = [r["label"] for r in rows]
    lo, hi = bootstrap_ci(y, pred)
    flo, fhi = bootstrap_ci(y, pred, metric=macro_f1)
    lines = [f"# {title}", "",
             f"- 문장 {len(rows)}개 · 정확도 **{accuracy(y, pred):.3f}** (95% CI {lo:.3f}–{hi:.3f}) · macro-F1 **{macro_f1(y, pred):.3f}** (95% CI {flo:.3f}–{fhi:.3f})",
             "- 신뢰구간은 문장 복원추출(부트스트랩 2000회). 표본이 작을수록 넓어집니다.", ""]
    for key, name in (("lang", "언어별"), ("source", "출처별"), ("hard", "hard 여부별"), ("label", "라벨별")):
        rs = breakdown(rows, pred, key)
        if len({k for k, _, _ in rs}) < 2 and key != "label":
            continue
        lines += [f"## {name}", "", "| 값 | 문장 수 | 정확도 |", "|---|---:|---:|"]
        lines += [f"| {k} | {n} | {a:.3f} |" for k, n, a in rs]
        lines.append("")
    cp = confusion_pairs(rows, pred)
    if cp:
        lines += ["## 자주 틀리는 쌍 (정답 → 예측)", "", "| 정답 | 예측 | 횟수 |", "|---|---|---:|"]
        lines += [f"| {t} | {p} | {n} |" for (t, p), n in cp]
    return "\n".join(lines)


# ───────────────────────── 테스트셋 재평가 방지 ─────────────────────────
def opened_tags():
    """테스트셋 v1을 이미 연 평가 이름 집합: frozen_v1.json의 기록 + 사용자 PC의 final_log.txt."""
    tags = set()
    if META.exists():
        tags |= set(json.loads(META.read_text(encoding="utf-8")).get("opened", {}))
    if LOG.exists():
        for line in LOG.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) >= 4 and parts[3] == "test":   # "날짜 시간 태그 test ..."
                tags.add(parts[2])
    return tags


def final_guard(tag, reopen=False):
    """같은 평가(tag)로 테스트셋 v1을 또 열려 하면 막는다. --reopen을 주면 허용하되 기록에 남긴다."""
    if tag in opened_tags():
        if not reopen:
            print(f"\n[중단] '{tag}' 는 이미 테스트셋 v1을 열어 본 평가입니다. 같은 테스트셋을 반복해서 보면 숫자가 낙관적으로 변합니다.\n"
                  f"       정말 다시 열려면 --reopen 을 붙이세요(보고서에 재평가 횟수를 적어야 합니다).")
            sys.exit(2)
        print(f"[주의] '{tag}' 테스트셋 v1 재평가(--reopen). 기록에 'REOPEN'으로 남깁니다.")
        return True
    return False


def log_final(tag, text, reopened=False):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {tag} test {text}{' REOPEN' if reopened else ''}\n")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    src = sys.argv[1]
    data = read_rows(src)
    rows = [{"label": d["label"], "lang": d.get("lang", ""), "source": d.get("source", ""), "hard": d.get("hard", "")} for d in data]
    out = report_md(rows, [d["pred"] for d in data], src.rsplit("/", 1)[-1])
    dst = src.rsplit(".", 1)[0] + "_report.md"
    open(dst, "w", encoding="utf-8").write(out)
    print(out)
