"""기준선: 글자 n-gram TF-IDF + 로지스틱 회귀 (CPU, 몇 초).
기본 실행은 학습/검증만 사용한다. 테스트셋은 `--final`로 딱 한 번 최종 평가할 때만 연다."""
import argparse
import json
import sys
import time

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from sklearn.feature_extraction.text import TfidfVectorizer  # noqa: E402
from sklearn.linear_model import LogisticRegression  # noqa: E402
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402

from common import FROZEN_TEST, LABELS, RESULTS, SPLITS, read_rows  # noqa: E402
from evalkit import dump_preds, final_guard, log_final, report_md  # noqa: E402

C_GRID = [0.3, 1, 3, 10, 30, 100]


def make_model(c):
    return make_pipeline(
        TfidfVectorizer(analyzer="char_wb", ngram_range=(1, 4), sublinear_tf=True, lowercase=True),
        LogisticRegression(C=c, max_iter=3000),
    )


def xy(rows):
    return [r["text"] for r in rows], [r["label"] for r in rows]


def plot_cm(y_true, y_pred, title, path):
    cm = confusion_matrix(y_true, y_pred, labels=LABELS)
    fig, ax = plt.subplots(figsize=(7.2, 6.2))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks(range(len(LABELS)), LABELS, rotation=45, ha="right")
    ax.set_yticks(range(len(LABELS)), LABELS)
    ax.set_xlabel("predicted")
    ax.set_ylabel("true")
    ax.set_title(title)
    for i in range(len(LABELS)):
        for j in range(len(LABELS)):
            if cm[i, j]:
                ax.text(j, i, cm[i, j], ha="center", va="center", color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, fraction=0.046)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    return cm


def write_errors(rows, y_pred, path, title):
    wrong = [(r, p) for r, p in zip(rows, y_pred) if r["label"] != p]
    lines = [f"# {title}: 오분류 {len(wrong)}개 / {len(rows)}개", "", "| 문장 | 정답 | 예측 | hard |", "|---|---|---|---|"]
    for r, p in wrong:
        lines.append(f"| {r['text']} | {r['label']} | {p} | {r['hard']} |")
    path.write_text("\n".join(lines), encoding="utf-8")
    return wrong


def learning_curve(train, val, c, path):
    rng = np.random.default_rng(0)
    xs, ys = [], []
    for frac in (0.25, 0.5, 0.75, 1.0):
        accs = []
        for _ in range(5 if frac < 1 else 1):
            idx = rng.permutation(len(train))[: max(len(LABELS), int(len(train) * frac))]
            sub = [train[i] for i in idx]
            if len({r["label"] for r in sub}) < len(LABELS):
                continue
            m = make_model(c).fit(*xy(sub))
            accs.append(accuracy_score([r["label"] for r in val], m.predict([r["text"] for r in val])))
        xs.append(int(len(train) * frac))
        ys.append(float(np.mean(accs)))
    fig, ax = plt.subplots(figsize=(5.2, 3.6))
    ax.plot(xs, ys, marker="o")
    ax.set_xlabel("training sentences")
    ax.set_ylabel("validation accuracy")
    ax.set_ylim(0, 1.02)
    ax.set_title("learning curve (TF-IDF + LR)")
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)
    return list(zip(xs, ys))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--final", action="store_true", help="테스트셋으로 최종 평가(한 번만!)")
    ap.add_argument("--reopen", action="store_true", help="이미 연 테스트셋을 같은 모델로 다시 평가(기록에 REOPEN 표시)")
    ap.add_argument("--test", default=FROZEN_TEST.name, help="splits 폴더 안의 테스트 파일 이름(기본: 동결 v1)")
    args = ap.parse_args()
    reopened = final_guard("baseline", args.reopen) if args.final else False
    RESULTS.mkdir(exist_ok=True)
    train, val = read_rows(SPLITS / "train.csv"), read_rows(SPLITS / "val.csv")

    # 1) 검증 정확도로 C 고르기
    best = None
    print(f"{'C':>6} {'val_acc':>8} {'val_macroF1':>12}")
    for c in C_GRID:
        m = make_model(c).fit(*xy(train))
        pred = m.predict([r["text"] for r in val])
        acc, f1 = accuracy_score([r["label"] for r in val], pred), f1_score([r["label"] for r in val], pred, average="macro")
        print(f"{c:>6} {acc:>8.3f} {f1:>12.3f}")
        if best is None or (acc, f1) > best[:2]:
            best = (acc, f1, c)
    c = best[2]
    print(f"\n선택한 C = {c}")

    model = make_model(c).fit(*xy(train))
    y_val = [r["label"] for r in val]
    pred = model.predict([r["text"] for r in val])
    report = classification_report(y_val, pred, labels=LABELS, zero_division=0)
    print(report)
    plot_cm(y_val, pred, f"validation confusion matrix (C={c})", RESULTS / "baseline_val_confusion.png")
    wrong = write_errors(val, pred, RESULTS / "baseline_val_errors.md", "검증셋")
    dump_preds(RESULTS / "preds_baseline_val.csv", val, list(pred), model.predict_proba([r["text"] for r in val]))
    (RESULTS / "baseline_val_breakdown.md").write_text(report_md(val, list(pred), "기준선 검증셋 분해"), encoding="utf-8")
    curve = learning_curve(train, val, c, RESULTS / "baseline_learning_curve.png")
    out = {"model": "tfidf_char_wb(1-4)+logreg", "C": c, "val_accuracy": best[0], "val_macro_f1": best[1],
           "val_wrong": len(wrong), "val_size": len(val), "learning_curve": curve}
    (RESULTS / "baseline_val.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    (RESULTS / "baseline_val_report.txt").write_text(report, encoding="utf-8")
    print("학습곡선:", ", ".join(f"{n}문장→{a:.2f}" for n, a in curve))

    if args.final:
        test = read_rows(SPLITS / args.test)
        final = make_model(c).fit(*xy(train + val))  # 선택된 C로 학습+검증을 합쳐 재학습
        y_test = [r["label"] for r in test]
        pt = final.predict([r["text"] for r in test])
        acc, f1 = accuracy_score(y_test, pt), f1_score(y_test, pt, average="macro")
        print(f"\n[최종 테스트] accuracy={acc:.3f} macroF1={f1:.3f} ({sum(a != b for a, b in zip(y_test, pt))}/{len(test)} 오분류)")
        plot_cm(y_test, pt, "TEST confusion matrix (baseline)", RESULTS / "baseline_test_confusion.png")
        write_errors(test, pt, RESULTS / "baseline_test_errors.md", "테스트셋")
        dump_preds(RESULTS / "preds_baseline_test.csv", test, list(pt), final.predict_proba([r["text"] for r in test]))
        (RESULTS / "baseline_test_breakdown.md").write_text(report_md(test, list(pt), "기준선 테스트셋 분해"), encoding="utf-8")
        (RESULTS / "baseline_test_report.txt").write_text(classification_report(y_test, pt, labels=LABELS, zero_division=0), encoding="utf-8")
        log_final("baseline", f"acc={acc:.3f} macroF1={f1:.3f}", reopened)  # 몇 번 열어봤는지 기록(정직성)
    return 0


if __name__ == "__main__":
    sys.exit(main())
