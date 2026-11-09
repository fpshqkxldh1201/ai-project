"""CLIP 감정분석 서버 흔적을 Lumi 프로젝트에서 정리하는 마이그레이션 스크립트.

사용법 (Lumi 프로젝트 폴더, build.gradle 이 있는 곳에서):
  python tools/migrate_emotion_server.py --dry-run     # 무엇이 바뀔지 먼저 보기 (파일은 건드리지 않음)
  python tools/migrate_emotion_server.py               # 실제로 적용

하는 일
  1. 자바 코드의 ClipAnalysisResponse → EmotionResult, ClipAnalysisRequest → EmotionRequest 이름 바꾸기
     (새 EmotionResult/EmotionRequest 파일은 zip에 들어 있다)
  2. 더 이상 쓰지 않는 dto/emotion/Clip*.java 두 파일 삭제
  3. 설정 키 clip.server.base-url → emotion.server.base-url (기본 포트 8002), 시작 진단 문구 수정
  4. 주석·문구의 'CLIP' 표현을 새 구조에 맞게 수정
  5. 마지막에 아직 'CLIP'이 남은 줄을 알려 준다 (직접 확인용)

여러 번 실행해도 안전하다(이미 바뀐 곳은 건드리지 않는다). 실행 전에 git commit 해 두면 되돌리기 쉽다.
"""
import argparse
import re
import sys
from pathlib import Path

JAVA_RENAMES = [
    (re.compile(r"\bClipAnalysisResponse\b"), "EmotionResult"),
    (re.compile(r"\bClipAnalysisRequest\b"), "EmotionRequest"),
]

# 문구 교체(주석·로그 문자열). 위에서부터 차례로 적용한다.
TEXT_REPLACEMENTS = [
    ('env.getProperty("clip.server.base-url", "")', 'env.getProperty("emotion.server.base-url", "http://localhost:8002")'),
    ("감정 분석(CLIP) 서버", "감정 분석 서버"),
    (" CLIP 감정분석 서버 (localhost:8000) :", " 감정분석 서버 (localhost:8002) :"),
    ("Soundwave의 CLIP 텍스트 인코더로도 분석해서", "직접 학습한 감정 분류 모델로도 분석해서"),
    ("Soundwave의 CLIP 서버", "Lumi 감정분석 서버"),
    ("CLIP으로 분석된", "감정 분석 모델로 분석된"),
    ("CLIP이 한 번에", "감정 분석 서버가 한 번에"),
    ("감정 분석(CLIP)에만", "감정 분석에만"),
    ("감정 분석(CLIP)", "감정 분석"),
    ("CLIP 감정분석", "감정 분석"),
    ("CLIP 서버", "감정 분석 서버"),
    ("CLIP 결과", "감정 분석 결과"),
    ("CLIP 텍스트 인코더", "감정 분류 모델"),
    ("Gemini/CLIP/네이버", "Gemini/감정분석/네이버"),
    ("Python CLIP", "Python 감정 분석"),
]
PROPERTY_RE = re.compile(r"^clip\.server\.base-url\s*=.*$", re.MULTILINE)

SCAN_GLOBS = [
    "src/**/*.java",
    "src/main/resources/static/**/*.js",
    "src/main/resources/static/**/*.html",
    "src/main/resources/application*.properties",
    "lumi-frontend/src/**/*.js",
    "lumi-frontend/src/**/*.jsx",
]
SKIP_PARTS = {"vendor", "node_modules"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    if not (root / "src" / "main" / "java" / "com" / "jarvisst" / "Lumi").exists():
        print("Lumi 프로젝트 폴더에서 실행하세요 (src/main/java/com/jarvisst/Lumi 가 보이는 곳).")
        return 1
    if not (root / "src/main/java/com/jarvisst/Lumi/dto/emotion/EmotionResult.java").exists():
        print("EmotionResult.java 가 없습니다. 먼저 zip의 src 폴더 파일들을 프로젝트에 복사하세요.")
        return 1

    changed, leftover = [], []
    seen = set()
    for pattern in SCAN_GLOBS:
        for path in sorted(root.glob(pattern)):
            if path in seen or any(p in SKIP_PARTS for p in path.parts):
                continue
            seen.add(path)
            if path.name in ("ClipAnalysisRequest.java", "ClipAnalysisResponse.java"):
                continue  # 아래에서 삭제
            raw = path.read_bytes()
            try:
                enc, text = "utf-8", raw.decode("utf-8")
            except UnicodeDecodeError:
                try:
                    enc, text = "cp949", raw.decode("cp949")  # 한글 윈도우 편집기로 저장된 파일
                except UnicodeDecodeError:
                    print(f"  건너뜀(인코딩을 알 수 없음): {path.relative_to(root)}")
                    continue
            new = text
            if path.suffix == ".java":
                for rx, rep in JAVA_RENAMES:
                    new = rx.sub(rep, new)
            if path.suffix == ".properties":
                new = PROPERTY_RE.sub("emotion.server.base-url=http://localhost:8002", new)
            for old, rep in TEXT_REPLACEMENTS:
                new = new.replace(old, rep)
            if new != text:
                changed.append(path)
                if not args.dry_run:
                    path.write_bytes(new.encode(enc))
            for n, line in enumerate(new.splitlines(), 1):
                if re.search(r"CLIP|clip\.server", line) and "clip-path" not in line.lower():
                    leftover.append(f"{path.relative_to(root)}:{n}: {line.strip()[:110]}")

    removed = []
    for name in ("ClipAnalysisRequest.java", "ClipAnalysisResponse.java"):
        p = root / "src/main/java/com/jarvisst/Lumi/dto/emotion" / name
        if p.exists():
            removed.append(p)
            if not args.dry_run:
                p.unlink()

    tag = "[미리보기] " if args.dry_run else ""
    print(f"{tag}수정한 파일 {len(changed)}개")
    for p in changed:
        print("  수정:", p.relative_to(root))
    for p in removed:
        print("  삭제:", p.relative_to(root))
    if leftover:
        print("\n아직 'CLIP'이 남은 줄 (직접 확인하세요):")
        for l in leftover:
            print("  ", l)
    else:
        print("\n'CLIP' 표현이 남은 곳이 없습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
