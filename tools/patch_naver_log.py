"""구글 검색 → NAVER 대체가 실패한 이유를 서버 로그에 남기는 작은 패치.
사용: 프로젝트 루트에서  python tools\\patch_naver_log.py   (여러 번 실행해도 안전)
"""
import sys
from pathlib import Path

path = Path("src/main/java/com/jarvisst/Lumi/service/GeminiService.java")
if not path.exists():
    sys.exit("프로젝트 루트(src 폴더가 보이는 곳)에서 실행하세요.")
raw = path.read_bytes()
bom = raw.startswith(b"\xef\xbb\xbf")
for enc in ("utf-8", "cp949"):
    try:
        text = raw.decode(enc).lstrip("﻿")
        break
    except UnicodeDecodeError:
        text = None
if text is None:
    sys.exit("인코딩을 알 수 없어 건드리지 않았어요.")

MARK = "[Google 검색 대체] NAVER 검색 결과 없음"
OLD = '                return Map.of("error", "Google 검색과 NAVER 검색을 지금은 모두 사용할 수 없습니다.'
NEW = ('                log.warn("[Google 검색 대체] NAVER 검색 결과 없음 — error={}, message={}", naver.get("error"), naver.get("message"));\n'
       + OLD)
if MARK in text:
    print("이미 적용됨")
elif text.count(OLD) != 1:
    sys.exit("못 찾음(수동 적용 필요): searchWithNaverInstead 의 error 반환 줄")
else:
    out = text.replace(OLD, NEW)
    data = out.encode(enc)
    path.write_bytes((b"\xef\xbb\xbf" + data) if bom else data)
    print("적용: NAVER 대체 실패 이유 로그")
