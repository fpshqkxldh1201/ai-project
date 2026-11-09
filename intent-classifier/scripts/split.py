"""학습/검증 분할 (v2). 테스트셋은 v1을 동결(test_v1_frozen.csv)해 그대로 두고, 나머지 문장을 85/15 로 나눈다.
비슷한 문장(유사도 ≥ 0.6)은 한 덩어리로 묶어 같은 쪽에만 들어가게 해서 누수를 막는다. 라벨별로 따로 나눠 비율을 유지한다.
동결 테스트와 비슷한(≥ 0.6) 새 문장은 학습에 쓰지 않고 따로 보고한다(validate.py도 같은 검사)."""
import random
import sys
from collections import Counter

from common import DATA, FROZEN_TEST, LABELS, SPLITS, grams, jaccard, read_rows, similar_pairs, write_rows

SEED = 42
GROUP_SIM = 0.6


def groups(rows):
    parent = list(range(len(rows)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for i, j, _ in similar_pairs(rows, GROUP_SIM):
        if rows[i]["label"] == rows[j]["label"]:  # 라벨이 다른 쌍은 validate.py가 따로 경고
            parent[find(i)] = find(j)
    out = {}
    for i in range(len(rows)):
        out.setdefault(find(i), []).append(rows[i])
    return list(out.values())


def main():
    allrows = read_rows(DATA / "intents.csv")
    frozen = read_rows(FROZEN_TEST)
    frozen_ids = {r["id"] for r in frozen}
    fg = [grams(r["text"]) for r in frozen]
    rows, leaked = [], []
    for r in allrows:
        if r["id"] in frozen_ids:
            continue
        g = grams(r["text"])
        if any(jaccard(g, x) >= GROUP_SIM for x in fg):
            leaked.append(r)       # 동결 테스트와 너무 비슷 → 학습/검증에서 제외
        else:
            rows.append(r)
    rng = random.Random(SEED)
    split = {"train": [], "val": []}
    for lab in LABELS:
        gs = groups([r for r in rows if r["label"] == lab])
        rng.shuffle(gs)
        total = sum(len(g) for g in gs)
        want = round(total * 0.15)
        got = 0
        for g in sorted(gs, key=len):  # 작은 덩어리부터 채워 비율을 맞추기 쉽게
            if got < want and got + len(g) <= want + (1 if len(g) > 1 else 0):
                split["val"].extend(g)
                got += len(g)
            else:
                split["train"].extend(g)
    for name, items in split.items():
        items.sort(key=lambda r: int(r["id"]))
        write_rows(SPLITS / f"{name}.csv", items)
    # 호환용: test.csv 는 동결본의 복사본(내용 동일). 실제 평가는 test_v1_frozen.csv 를 쓴다.
    write_rows(SPLITS / "test.csv", frozen)
    split["test(v1 동결)"] = frozen
    lines = ["분할 결과 (seed=%d, test = v1 동결)" % SEED, f"{'label':<14}{'train':>6}{'val':>5}{'test':>6}"]
    names = list(split)
    for lab in LABELS:
        lines.append(f"{lab:<14}" + "".join(f"{sum(r['label']==lab for r in split[n]):>{w}}" for n, w in zip(names, (6, 5, 6))))
    lines.append(f"합계 train {len(split['train'])} / val {len(split['val'])} / test {len(frozen)}")
    lines.append("언어: " + ", ".join(f"{n}={dict(Counter(r['lang'] for r in split[n]))}" for n in names))
    lines.append("출처: " + ", ".join(f"{n}={dict(Counter(r['source'] for r in split[n]))}" for n in names))
    lines.append("어려운 문장(hard): " + ", ".join(f"{n}={sum(r['hard']=='1' for r in split[n])}" for n in names))
    lines.append(f"동결 테스트와 너무 비슷해 제외한 새 문장: {len(leaked)}개" + "".join(f"\n  - {r['text']}" for r in leaked))
    text = "\n".join(lines)
    (SPLITS / "split_report.txt").write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
