"""common.py 에 출처 'massive' 와 버전 'v3' 를 허용하도록 고친다(validate.py 가 이 값을 오류로 보지 않게).
여러 번 실행해도 안전하다(이미 고쳐졌으면 아무것도 안 함). 원본은 common.py.bak 으로 남긴다.

  python patch_massive.py --dry-run   # 바뀔 내용만 보기
  python patch_massive.py
"""
import argparse
import shutil
import sys
from pathlib import Path

P = Path(__file__).resolve().parent / "common.py"
EDITS = [
    ('SOURCES = ("ai", "peer", "self")          # AI 초안 / 급우 제출 / 직접 작성',
     'SOURCES = ("ai", "peer", "self", "massive")  # AI 초안 / 급우 제출 / 직접 작성 / 공개 데이터 MASSIVE(한국어)'),
    ('VERSIONS = ("v1", "v1.1", "v2")', 'VERSIONS = ("v1", "v1.1", "v2", "v3")'),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    raw = P.read_bytes()
    crlf = b"\r\n" in raw
    s = raw.decode("utf-8").replace("\r\n", "\n")
    changed = 0
    for old, new in EDITS:
        if new in s:
            continue
        if old not in s:
            sys.exit(f"예상한 줄을 찾지 못했습니다(common.py가 달라졌나요?): {old[:40]}…")
        s = s.replace(old, new, 1)
        changed += 1
    if not changed:
        print("이미 적용돼 있습니다.")
        return 0
    print(f"{changed}곳 수정" + (" (dry-run: 저장 안 함)" if a.dry_run else ""))
    if a.dry_run:
        return 0
    shutil.copyfile(P, P.with_suffix(".py.bak"))
    P.write_bytes((s.replace("\n", "\r\n") if crlf else s).encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
