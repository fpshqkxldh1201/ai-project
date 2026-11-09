"""테스트셋 v1을 '동결'한다: 파일 복사 + SHA-256 + 이미 공개한 결과 기록.
이미 동결돼 있으면 해시만 검증한다. (Phase 0에서 한 번 실행했고, 이후엔 verify 용도)"""
import hashlib
import json
import shutil
import sys

from common import FROZEN_TEST, RESULTS, SPLITS, read_rows

META = RESULTS / "frozen_v1.json"

# 이미 테스트셋 v1을 열어 본 모델과 그때 나온 숫자(사용자 PC 실행 결과). 같은 모델로 다시 열지 않는다.
OPENED = {
    "baseline": {"acc": 0.776, "macro_f1": 0.767, "wrong": "11/49", "note": "TF-IDF char_wb(1-4)+LR"},
    "nn-finetune": {"acc": "0.925±0.010", "macro_f1": "0.924±0.009", "epochs": 28, "seeds": 3,
                    "note": "multilingual-e5-small 미세조정, 학습+검증 재학습"},
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    if not META.exists() or not FROZEN_TEST.exists():
        return False, "동결 파일이 없습니다 (python freeze_v1.py 먼저)"
    meta = json.loads(META.read_text(encoding="utf-8"))
    now = sha256(FROZEN_TEST)
    if now != meta["sha256"]:
        return False, f"test_v1_frozen.csv 가 바뀌었습니다! (기록 {meta['sha256'][:12]}… / 현재 {now[:12]}…)"
    return True, f"OK ({meta['size']}문장, sha256 {now[:12]}…)"


def main():
    if FROZEN_TEST.exists() and META.exists():
        ok, msg = verify()
        print(msg)
        return 0 if ok else 1
    src = SPLITS / "test.csv"
    if not FROZEN_TEST.exists():
        shutil.copyfile(src, FROZEN_TEST)
    rows = read_rows(FROZEN_TEST)
    meta = {"file": "data/splits/test_v1_frozen.csv", "size": len(rows), "sha256": sha256(FROZEN_TEST),
            "ids": [int(r["id"]) for r in rows], "opened": OPENED,
            "rule": "이 파일은 수정·재분할·학습 사용 금지. 같은 모델로 다시 평가하려면 --reopen 이 필요하고 보고서에 횟수를 적는다."}
    META.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"동결 완료: {len(rows)}문장, sha256 {meta['sha256'][:12]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
