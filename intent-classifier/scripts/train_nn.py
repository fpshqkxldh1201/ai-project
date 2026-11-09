"""신경망 학습: 다국어 문장 임베딩 인코더(기본 intfloat/multilingual-e5-small) 위에 의도 분류기를 직접 학습한다.

  python train_nn.py                  # 헤드 학습: 인코더는 고정, 그 위의 작은 MLP를 학습 (5 시드, CPU에서 수십 초)
  python train_nn.py --finetune       # 인코더까지 같이 미세조정 (CPU에서 수 분, 1 시드)
  python train_nn.py --final          # 테스트셋 최종 평가(한 번만!) — 검증으로 정한 에폭 수로 학습+검증 데이터로 재학습

기록: 에폭마다 train/val 손실·정확도(results/nn_*_history.csv, nn_*_curves.png), confusion matrix, 오분류 목록.
"""
import argparse
import copy
import hashlib
import json
import math
import random
import sys
import time

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import torch  # noqa: E402
import torch.nn as nn  # noqa: E402
import torch.nn.functional as F  # noqa: E402
from sklearn.metrics import accuracy_score, classification_report, f1_score  # noqa: E402

from baseline import plot_cm, write_errors  # noqa: E402
from common import DATA, FROZEN_TEST, LABELS, RESULTS, ROOT, SPLITS, read_rows  # noqa: E402
from evalkit import dump_preds, final_guard, log_final, report_md  # noqa: E402

DEFAULT_ENCODER = "intfloat/multilingual-e5-small"
DEFAULT_PREFIX = "query: "  # e5 계열은 입력 앞에 이 접두어를 붙이는 것이 규칙이다
MAX_LEN = 48
MODELS = ROOT / "models"
CACHE = DATA / "cache"
L2I = {l: i for i, l in enumerate(LABELS)}


# ───────────────────────── 인코더 ─────────────────────────
class HFEncoder(nn.Module):
    """HuggingFace 인코더 + 평균 풀링 + L2 정규화. embed(texts) -> (B, D)."""

    def __init__(self, name, prefix=DEFAULT_PREFIX, max_len=MAX_LEN):
        super().__init__()
        from transformers import AutoModel, AutoTokenizer

        self.name, self.prefix, self.max_len = name, prefix, max_len
        self.tok = AutoTokenizer.from_pretrained(name)
        self.model = AutoModel.from_pretrained(name)
        self.dim = self.model.config.hidden_size

    def embed(self, texts):
        dev = next(self.model.parameters()).device
        batch = self.tok([self.prefix + t for t in texts], padding=True, truncation=True,
                         max_length=self.max_len, return_tensors="pt").to(dev)
        out = self.model(**batch).last_hidden_state
        mask = batch["attention_mask"].unsqueeze(-1).to(out.dtype)
        pooled = (out * mask).sum(1) / mask.sum(1).clamp(min=1)
        return F.normalize(pooled, dim=-1)


class Classifier(nn.Module):
    def __init__(self, encoder, n_classes, hidden=128, dropout=0.3):
        super().__init__()
        self.encoder = encoder
        self.head = nn.Sequential(
            nn.Linear(encoder.dim, hidden), nn.GELU(), nn.Dropout(dropout), nn.Linear(hidden, n_classes)
        )


# ───────────────────────── 임베딩 캐시(헤드 모드) ─────────────────────────
def _key(encoder_name, prefix, text):
    return hashlib.sha1(f"{encoder_name}|{prefix}|{text}".encode("utf-8")).hexdigest()


@torch.no_grad()
def embed_rows(encoder, rows, name, prefix, cache=True, batch=64):
    """문장 임베딩 (n, D). 한 번 계산한 것은 data/cache에 저장해 다음 실행부터 재사용한다."""
    store = {}
    path = CACHE / (hashlib.sha1(f"{name}|{prefix}".encode()).hexdigest()[:12] + ".npz")
    if cache and path.exists():
        z = np.load(path, allow_pickle=False)
        store = dict(zip(z["keys"].tolist(), z["vecs"]))
    texts = [r["text"] for r in rows]
    missing = [t for t in dict.fromkeys(texts) if _key(name, prefix, t) not in store]
    if missing:
        encoder.eval()
        for i in range(0, len(missing), batch):
            chunk = missing[i:i + batch]
            vecs = encoder.embed(chunk).cpu().numpy()
            for t, v in zip(chunk, vecs):
                store[_key(name, prefix, t)] = v
        if cache:
            CACHE.mkdir(parents=True, exist_ok=True)
            np.savez(path, keys=np.array(list(store.keys())), vecs=np.stack(list(store.values())))
    return torch.tensor(np.stack([store[_key(name, prefix, t)] for t in texts]), dtype=torch.float32)


# ───────────────────────── 학습 ─────────────────────────
def set_seed(seed):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def labels_of(rows):
    return torch.tensor([L2I[r["label"]] for r in rows], dtype=torch.long)


class Trainer:
    def __init__(self, encoder, name, prefix, finetune, device):
        self.encoder, self.name, self.prefix, self.finetune, self.device = encoder, name, prefix, finetune, device

    def _features(self, model, rows, X):
        """배치 단위 특징 함수. 헤드 모드는 캐시된 임베딩, 미세조정 모드는 매번 인코더를 통과."""
        if self.finetune:
            return lambda idx: model.encoder.embed([rows[i]["text"] for i in idx])
        return lambda idx: X[idx]

    @torch.no_grad()
    def evaluate(self, model, rows, X, bs=64):
        model.eval()
        feat = self._features(model, rows, X)
        logits = torch.cat([model.head(feat(list(range(i, min(i + bs, len(rows)))))) for i in range(0, len(rows), bs)])
        y = labels_of(rows).to(logits.device)
        return F.cross_entropy(logits, y).item(), (logits.argmax(1) == y).float().mean().item(), logits

    def fit(self, train, val=None, epochs=300, patience=25, seed=0, bs=32, fixed_epochs=None, log=print):
        """val이 있으면 검증 손실이 가장 낮은 에폭의 가중치를 쓴다(조기 종료). val이 없으면 fixed_epochs만큼 학습."""
        set_seed(seed)
        model = Classifier(self.encoder, len(LABELS)).to(self.device)
        for p in model.encoder.parameters():
            p.requires_grad_(self.finetune)
        Xtr = None if self.finetune else embed_rows(self.encoder, train, self.name, self.prefix).to(self.device)
        Xva = None if (self.finetune or val is None) else embed_rows(self.encoder, val, self.name, self.prefix).to(self.device)
        ytr = labels_of(train).to(self.device)

        groups = [{"params": model.head.parameters(), "lr": 3e-3 if not self.finetune else 1e-3, "weight_decay": 1e-2}]
        if self.finetune:
            groups.append({"params": model.encoder.parameters(), "lr": 3e-5, "weight_decay": 1e-2})
        opt = torch.optim.AdamW(groups)
        total = fixed_epochs or epochs
        steps = total * math.ceil(len(train) / bs)
        warm = max(1.0, steps * 0.1)
        # 미세조정: 처음 10%는 학습률을 서서히 올리고(warm-up) 이후 0까지 선형으로 줄인다. 헤드 모드는 상수.
        sched = torch.optim.lr_scheduler.LambdaLR(
            opt, lambda st: min(1.0, (st + 1) / warm) * max(0.0, 1 - st / steps)
        ) if self.finetune else None

        trainable = (lambda: copy.deepcopy(model.state_dict())) if self.finetune else (lambda: copy.deepcopy(model.head.state_dict()))
        history, best, best_state, bad = [], (float("inf"), 0), None, 0
        gen = torch.Generator().manual_seed(seed)
        for ep in range(1, total + 1):
            model.train()
            if not self.finetune:
                model.encoder.eval()
            feat = self._features(model, train, Xtr)
            perm = torch.randperm(len(train), generator=gen).tolist()
            for i in range(0, len(perm), bs):
                idx = perm[i:i + bs]
                loss = F.cross_entropy(model.head(feat(idx)), ytr[idx])
                opt.zero_grad()
                loss.backward()
                opt.step()
                if sched:
                    sched.step()
            tl, ta, _ = self.evaluate(model, train, Xtr)
            row = {"epoch": ep, "train_loss": tl, "train_acc": ta}
            if val is not None:
                vl, va, _ = self.evaluate(model, val, Xva)
                row.update(val_loss=vl, val_acc=va)
                if vl < best[0] - 1e-4:
                    best, best_state, bad = (vl, ep), trainable(), 0
                else:
                    bad += 1
            history.append(row)
            if ep == 1 or ep % (1 if self.finetune else 10) == 0:
                log(f"  epoch {ep:>3}  train loss {tl:.4f} acc {ta:.3f}" + (f" | val loss {row['val_loss']:.4f} acc {row['val_acc']:.3f}" if val is not None else ""))
            if val is not None and bad >= patience:
                log(f"  조기 종료: 검증 손실이 {patience}에폭 동안 좋아지지 않음 (best epoch {best[1]})")
                break
        if val is not None and best_state is not None:
            (model if self.finetune else model.head).load_state_dict(best_state)
            best_epoch = best[1]
        else:
            best_epoch = len(history)
        return model, history, best_epoch


# ───────────────────────── 그림/기록 ─────────────────────────
def plot_curves(histories, best_epochs, title, path):
    fig, ax = plt.subplots(1, 2, figsize=(10, 3.8))
    for k, h in enumerate(histories):
        e = [r["epoch"] for r in h]
        main = k == 0
        a = 1.0 if main else 0.25
        lw = 2.0 if main else 1.0
        ax[0].plot(e, [r["train_loss"] for r in h], color="tab:blue", alpha=a, lw=lw, label="train" if main else None)
        ax[0].plot(e, [r["val_loss"] for r in h], color="tab:red", alpha=a, lw=lw, label="validation" if main else None)
        ax[1].plot(e, [r["train_acc"] for r in h], color="tab:blue", alpha=a, lw=lw, label="train" if main else None)
        ax[1].plot(e, [r["val_acc"] for r in h], color="tab:red", alpha=a, lw=lw, label="validation" if main else None)
    ax[0].axvline(best_epochs[0], color="gray", ls="--", lw=1, label=f"best epoch ({best_epochs[0]})")
    ax[1].axvline(best_epochs[0], color="gray", ls="--", lw=1)
    ax[0].set_title("loss")
    ax[1].set_title("accuracy")
    ax[1].set_ylim(0, 1.02)
    for a_ in ax:
        a_.set_xlabel("epoch")
        a_.grid(alpha=0.3)
    ax[0].legend()
    fig.suptitle(title)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def write_history(h, path):
    keys = list(h[0].keys())
    path.write_text("\n".join([",".join(keys)] + [",".join(f"{r[k]:.6f}" if isinstance(r[k], float) else str(r[k]) for k in keys) for r in h]), encoding="utf-8")


def compare_table(mode, nn_res):
    base = RESULTS / "baseline_val.json"
    n_val = len(read_rows(SPLITS / "val.csv"))
    lines = [f"# 검증셋 비교 ({n_val}문장 — 한 문장 ≈ {100 / n_val:.1f}%p, 대략적으로만 해석)", "", "| 모델 | accuracy | macro-F1 |", "|---|---|---|"]
    if base.exists():
        b = json.loads(base.read_text(encoding="utf-8"))
        lines.append(f"| 기준선 TF-IDF+LR (C={b['C']}) | {b['val_accuracy']:.3f} | {b['val_macro_f1']:.3f} |")
    lines.append(f"| {nn_res['name']} | {nn_res['acc_mean']:.3f} ± {nn_res['acc_std']:.3f} | {nn_res['f1_mean']:.3f} ± {nn_res['f1_std']:.3f} |")
    (RESULTS / f"compare_val_{mode}.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n" + "\n".join(lines))


# ───────────────────────── 실행 ─────────────────────────
def run(args, encoder=None):
    RESULTS.mkdir(exist_ok=True)
    MODELS.mkdir(exist_ok=True)
    device = "cuda" if torch.cuda.is_available() and not args.cpu else "cpu"
    mode = "finetune" if args.finetune else "head"
    args.reopened = final_guard(f"nn-{mode}", args.reopen) if args.final else False  # 학습 전에 막아야 시간을 안 버린다
    if encoder is None:
        print(f"인코더 불러오는 중: {args.encoder} (처음 한 번은 모델을 내려받느라 몇 분 걸릴 수 있어요)")
        encoder = HFEncoder(args.encoder, args.prefix)
    encoder = encoder.to(device)
    train, val = read_rows(SPLITS / "train.csv"), read_rows(SPLITS / "val.csv")
    seeds = list(range(args.seeds if args.seeds else (1 if args.finetune else 5)))
    epochs = args.epochs or (6 if args.finetune else 300)
    patience = args.patience or (2 if args.finetune else 25)
    tr = Trainer(encoder, args.encoder, args.prefix, args.finetune, device)
    print(f"모드={mode}  device={device}  시드={seeds}  train={len(train)} val={len(val)}")

    runs = []
    for s in seeds:
        print(f"[seed {s}]")
        t0 = time.time()
        model, hist, best_ep = tr.fit(train, val, epochs=epochs, patience=patience, seed=s)
        Xva = None if args.finetune else embed_rows(encoder, val, args.encoder, args.prefix).to(device)
        vl, va, logits = tr.evaluate(model, val, Xva)
        pred = [LABELS[i] for i in logits.argmax(1).tolist()]
        probs = F.softmax(logits, 1).cpu().numpy()
        f1 = f1_score([r["label"] for r in val], pred, average="macro")
        print(f"  → best epoch {best_ep}  val loss {vl:.4f}  val acc {va:.3f}  macro-F1 {f1:.3f}  ({time.time()-t0:.0f}s)")
        runs.append({"seed": s, "model": model, "hist": hist, "best": best_ep, "acc": va, "f1": f1, "pred": pred, "probs": probs, "vl": vl})

    accs, f1s = np.array([r["acc"] for r in runs]), np.array([r["f1"] for r in runs])
    res = {"name": f"{'미세조정' if args.finetune else '고정 인코더+MLP'} ({args.encoder.split('/')[-1]}), {len(seeds)}시드",
           "acc_mean": float(accs.mean()), "acc_std": float(accs.std()), "f1_mean": float(f1s.mean()), "f1_std": float(f1s.std())}
    main = runs[0]
    y_val = [r["label"] for r in val]
    plot_curves([r["hist"] for r in runs], [r["best"] for r in runs], f"{mode}: loss / accuracy per epoch (seed 0 bold)", RESULTS / f"nn_{mode}_curves.png")
    write_history(main["hist"], RESULTS / f"nn_{mode}_history.csv")
    plot_cm(y_val, main["pred"], f"{mode} validation confusion matrix (seed 0)", RESULTS / f"nn_{mode}_val_confusion.png")
    write_errors(val, main["pred"], RESULTS / f"nn_{mode}_val_errors.md", f"검증셋({mode}, seed 0)")
    dump_preds(RESULTS / f"preds_nn_{mode}_val.csv", val, main["pred"], main["probs"])
    (RESULTS / f"nn_{mode}_val_breakdown.md").write_text(report_md(val, main["pred"], f"{mode} 검증셋 분해 (seed 0)"), encoding="utf-8")
    report = classification_report(y_val, main["pred"], labels=LABELS, zero_division=0)
    (RESULTS / f"nn_{mode}_val_report.txt").write_text(report, encoding="utf-8")
    print("\n" + report)
    meta = {"mode": mode, "encoder": args.encoder, "prefix": args.prefix, "max_len": MAX_LEN, "labels": LABELS,
            "best_epoch": main["best"], "val_accuracy": main["acc"], "val_macro_f1": main["f1"], "seeds": len(seeds),
            "val_acc_mean": res["acc_mean"], "val_acc_std": res["acc_std"]}
    (RESULTS / f"nn_{mode}_val.json").write_text(json.dumps({**meta, **res}, ensure_ascii=False, indent=2), encoding="utf-8")
    compare_table(mode, res)

    if args.finetune:
        torch.save(main["model"].state_dict(), MODELS / "intent_finetune.pt")  # 용량이 크다(수백 MB) — 커밋 금지
    else:
        torch.save(main["model"].head.state_dict(), MODELS / "intent_head.pt")
    (MODELS / f"intent_{mode}_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.final:
        final_eval(args, encoder, tr, train, val, runs, device, mode)
    return runs


def final_eval(args, encoder, tr, train, val, runs, device, mode):
    """테스트셋 평가(한 번만). 검증으로 정한 에폭 수(시드별 best epoch의 중앙값)만큼 학습+검증 데이터로 재학습한다."""
    test = read_rows(SPLITS / args.test)
    ep = int(np.median([r["best"] for r in runs]))
    print(f"\n[최종 테스트] 학습+검증 {len(train)+len(val)}문장으로 {ep}에폭 재학습 후 테스트 {len(test)}문장 평가")
    y = [r["label"] for r in test]
    accs, f1s, first = [], [], None
    for s in range(len(runs)):
        model, _, _ = tr.fit(train + val, None, fixed_epochs=ep, seed=s, log=lambda *_: None)
        X = None if args.finetune else embed_rows(encoder, test, args.encoder, args.prefix).to(device)
        _, acc, logits = tr.evaluate(model, test, X)
        pred = [LABELS[i] for i in logits.argmax(1).tolist()]
        accs.append(acc)
        f1s.append(f1_score(y, pred, average="macro"))
        if first is None:
            first = (model, pred, F.softmax(logits, 1).cpu().numpy())
    print(f"  accuracy {np.mean(accs):.3f} ± {np.std(accs):.3f}   macro-F1 {np.mean(f1s):.3f} ± {np.std(f1s):.3f}   ({len(runs)}시드)")
    plot_cm(y, first[1], f"TEST confusion matrix ({mode}, seed 0)", RESULTS / f"nn_{mode}_test_confusion.png")
    write_errors(test, first[1], RESULTS / f"nn_{mode}_test_errors.md", f"테스트셋({mode}, seed 0)")
    dump_preds(RESULTS / f"preds_nn_{mode}_test.csv", test, first[1], first[2])
    (RESULTS / f"nn_{mode}_test_breakdown.md").write_text(report_md(test, first[1], f"{mode} 테스트셋 분해 (seed 0)"), encoding="utf-8")
    (RESULTS / f"nn_{mode}_test_report.txt").write_text(classification_report(y, first[1], labels=LABELS, zero_division=0), encoding="utf-8")
    (RESULTS / f"nn_{mode}_test.json").write_text(json.dumps({"mode": mode, "epochs": ep, "acc_mean": float(np.mean(accs)), "acc_std": float(np.std(accs)),
                                                              "f1_mean": float(np.mean(f1s)), "f1_std": float(np.std(f1s)), "seeds": len(runs)}, indent=2), encoding="utf-8")
    log_final(f"nn-{mode}", f"acc={np.mean(accs):.3f}±{np.std(accs):.3f} macroF1={np.mean(f1s):.3f}", args.reopened)
    if args.finetune:
        torch.save(first[0].state_dict(), MODELS / "intent_finetune_final.pt")
    else:
        torch.save(first[0].head.state_dict(), MODELS / "intent_head_final.pt")


def parse(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--encoder", default=DEFAULT_ENCODER)
    ap.add_argument("--prefix", default=DEFAULT_PREFIX, help="입력 앞에 붙일 접두어(e5 계열은 'query: ')")
    ap.add_argument("--finetune", action="store_true", help="인코더까지 미세조정")
    ap.add_argument("--final", action="store_true", help="테스트셋으로 최종 평가(한 번만!)")
    ap.add_argument("--seeds", type=int, default=0, help="시드 개수(기본: 헤드 5, 미세조정 1)")
    ap.add_argument("--epochs", type=int, default=0)
    ap.add_argument("--patience", type=int, default=0)
    ap.add_argument("--reopen", action="store_true", help="이미 연 테스트셋을 같은 모델로 다시 평가(기록에 REOPEN 표시)")
    ap.add_argument("--test", default=FROZEN_TEST.name, help="splits 폴더 안의 테스트 파일 이름(기본: 동결 v1)")
    ap.add_argument("--cpu", action="store_true", help="GPU가 있어도 CPU 사용")
    return ap.parse_args(argv)


if __name__ == "__main__":
    run(parse())
    sys.exit(0)
