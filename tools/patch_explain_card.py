"""개념 설명 답변을 카드로 띄우는 패치 (ChatController 한 곳). 여러 번 실행해도 안전.

사용법 (프로젝트 루트, src 폴더가 보이는 곳):
  python tools/patch_explain_card.py --dry-run
  python tools/patch_explain_card.py
먼저 zip 의 ResultCardBuilder.java 를 같은 경로에 덮어쓰세요.
"""
import argparse
from pathlib import Path

PATH = Path("src/main/java/com/jarvisst/Lumi/controller/ChatController.java")
STEPS = [
    ("import", "import com.jarvisst.Lumi.service.ResultCardBuilder;",
     "import com.jarvisst.Lumi.service.ResultCardContext;",
     "import com.jarvisst.Lumi.service.ResultCardBuilder;\nimport com.jarvisst.Lumi.service.ResultCardContext;"),
    ("카드 없는 개념 설명 → 카드", "ResultCardBuilder.withExplanation(",
     ": ResultCardContext.drain();",
     ": ResultCardBuilder.withExplanation(ResultCardContext.drain(), rawMessage, result.reply());"),
]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--dry-run", action="store_true"); a = ap.parse_args()
    if not PATH.exists():
        raise SystemExit("ChatController.java 를 못 찾았어요. 프로젝트 루트에서 실행하세요.")
    raw = PATH.read_bytes(); enc = "utf-8"
    try: text = raw.decode("utf-8")
    except UnicodeDecodeError: enc = "cp949"; text = raw.decode("cp949")
    out, bad = text, 0
    for name, marker, anchor, new in STEPS:
        if marker in out: print("  이미 적용됨:", name)
        elif out.count(anchor) != 1: print("  !! 못 찾음(수동 적용 필요):", name); bad += 1
        else: out = out.replace(anchor, new); print("  적용:", name)
    if out != text and not a.dry_run: PATH.write_bytes(out.encode(enc))
    print("완료" if not bad else f"수동 확인 필요 {bad}건 (먼저 patch_result_cards_backend.py 가 적용돼 있어야 해요)")

main()
