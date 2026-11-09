r"""Lumi 의도 분류기 데이터에서 '감정이 없는 명령 문장'을 뽑아 감정 모델의 추가 학습 데이터로 만든다.

  python scripts\prepare_extra.py                               # 기본: ..\intent-classifier\data 를 읽음
  python scripts\prepare_extra.py --intent-data D:\경로\data

만드는 파일(data\):
  extra_command_train.csv  명령 문장 학습용 — 일정·알림·노트·검색 명령(train 분할) + 범위 밖 요청(oos_train)
  extra_command_val.csv    명령 문장 검증용 — 조기 종료·중립 기준·게이트 기준을 정하는 데만 사용
  extra_chat_train.csv     감정을 털어놓는 대화(chat_emotion, train) — 게이트의 '명령 아님' 예시
  extra_chat_val.csv       같은 종류의 검증용

원칙
  - 의도 분류기의 **평가용 분할(test, test_v1_frozen)은 읽지 않는다.** 이 스크립트는 그 파일 이름을 아예 열지 않는다.
  - data\lumi_check.csv(Lumi 대화체 점검 문장)와 같은 문장은 모두 뺀다(점검이 학습에 새면 점검의 의미가 없어진다).
  - 이 문장들은 AI가 쓴 것이다. 보고서에는 "감정 없는 명령 문장은 의도 분류기용 AI 작성 데이터"라고 밝혀야 한다.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
CHECK = DATA / "lumi_check.csv"


def norm(s: str) -> str:
    """띄어쓰기·문장부호·대소문자 차이를 무시하고 같은 문장인지 비교하기 위한 형태."""
    return re.sub(r"[\W_]+", "", s.lower())


def read_rows(path: Path):
    if not path.exists():
        sys.exit(f"파일을 찾을 수 없습니다: {path}")
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def write(path: Path, rows) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["text", "lang", "source"])
        w.writerows(rows)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--intent-data", default=str(ROOT.parent / "intent-classifier" / "data"))
    ap.add_argument("--out", default=str(DATA))
    args = ap.parse_args()
    src, out = Path(args.intent_data), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    check = set()
    if CHECK.exists():
        check = {norm(r["text"]) for r in read_rows(CHECK)}
    else:
        print(f"참고: {CHECK} 가 없어 점검 문장 제외는 건너뜁니다(학습 때 train.py가 한 번 더 확인합니다).")

    train = read_rows(src / "splits" / "train.csv")
    val = read_rows(src / "splits" / "val.csv")
    oos = read_rows(src / "oos_train.csv")

    cmd_tr = [(r["text"].strip(), r.get("lang", ""), r["label"]) for r in train if r["label"] != "chat_emotion"]
    cmd_tr += [(r["text"].strip(), r.get("lang", ""), "oos_" + r["category"]) for r in oos]
    cmd_va = [(r["text"].strip(), r.get("lang", ""), r["label"]) for r in val if r["label"] != "chat_emotion"]
    chat_tr = [(r["text"].strip(), r.get("lang", ""), "chat_emotion") for r in train if r["label"] == "chat_emotion"]
    chat_va = [(r["text"].strip(), r.get("lang", ""), "chat_emotion") for r in val if r["label"] == "chat_emotion"]

    seen: set = set()
    dropped_check = dropped_dup = 0

    def clean(rows):
        nonlocal dropped_check, dropped_dup
        keep = []
        for t, lang, lab in rows:
            k = norm(t)
            if not k:
                continue
            if k in check:
                dropped_check += 1
                continue
            if k in seen:
                dropped_dup += 1
                continue
            seen.add(k)
            keep.append((t, lang, lab))
        return keep

    # 학습 → 검증 순으로 걸러 같은 문장이 두 쪽에 동시에 들어가지 않게 한다
    cmd_tr, chat_tr, cmd_va, chat_va = clean(cmd_tr), clean(chat_tr), clean(cmd_va), clean(chat_va)
    write(out / "extra_command_train.csv", cmd_tr)
    write(out / "extra_command_val.csv", cmd_va)
    write(out / "extra_chat_train.csv", chat_tr)
    write(out / "extra_chat_val.csv", chat_va)
    print(f"명령 학습 {len(cmd_tr)} / 명령 검증 {len(cmd_va)} / 대화 학습 {len(chat_tr)} / 대화 검증 {len(chat_va)}")
    print(f"점검 문장과 같아서 뺀 것 {dropped_check}개, 중복으로 뺀 것 {dropped_dup}개")
    print(f"저장: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
