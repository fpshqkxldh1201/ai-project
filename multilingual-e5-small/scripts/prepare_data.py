"""KOTE(Korean Online That-gul Emotions) 데이터를 내려받아 data/kote/ 에 둔다.

  python scripts/prepare_data.py

- 출처: https://github.com/searle-j/KOTE (MIT 라이선스, 논문 arXiv:2205.05300)
- 구성: 온라인 댓글 5만 개를 44개 정서(없음 포함)로 라벨링. train 40,000 / val 5,000 / test 5,000.
- 이미 받은 파일이 있으면 다시 받지 않고 개수만 확인한다.
"""
import csv
import hashlib
import sys
import urllib.request
from collections import Counter
from pathlib import Path

from emotion_labels import LABELS

ROOT = Path(__file__).resolve().parent.parent
KOTE_DIR = ROOT / "data" / "kote"
BASE = "https://raw.githubusercontent.com/searle-j/KOTE/main/"
FILES = {"train": 40000, "val": 5000, "test": 5000}


def read_tsv(path: Path):
    """[(id, text, [정서 번호, ...]), ...]"""
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if len(r) < 3 or not r[2].strip():
                continue
            rows.append((r[0], r[1].strip(), [int(x) for x in r[2].split(",")]))
    return rows


def main() -> int:
    KOTE_DIR.mkdir(parents=True, exist_ok=True)
    for name, expected in FILES.items():
        path = KOTE_DIR / f"{name}.tsv"
        if not path.exists():
            print(f"내려받는 중: {name}.tsv ...")
            urllib.request.urlretrieve(BASE + f"{name}.tsv", path)
        rows = read_tsv(path)
        sha = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
        status = "OK" if len(rows) == expected else f"개수 이상! (기대 {expected})"
        print(f"{name:>5}: {len(rows):>6}문장  sha256 {sha}…  {status}")
        if len(rows) != expected:
            return 1
        if name == "train":
            cnt = Counter(i for _, _, ids in rows for i in ids)
            if max(cnt) != len(LABELS) - 1:
                print("라벨 번호 범위가 예상과 다릅니다. emotion_labels.py의 라벨 순서를 확인하세요.")
                return 1
            top = ", ".join(f"{LABELS[i]} {c}" for i, c in cnt.most_common(5))
            print(f"       가장 많은 정서: {top}")
    print("\n준비 완료. 다음: python scripts/train.py --mode head")
    return 0


if __name__ == "__main__":
    sys.exit(main())
