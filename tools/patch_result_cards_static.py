"""Lumi 결과 카드를 8081 정적 화면(src/main/resources/static)에 연결하는 패치 스크립트.

사용법 (Spring 프로젝트 루트, 즉 src 폴더가 보이는 곳에서):
  python tools/patch_result_cards_static.py --dry-run   # 무엇이 바뀔지 보기
  python tools/patch_result_cards_static.py             # 적용

먼저 zip의 static/js/result-stage.js, static/css/result-stage.css 를 같은 경로에 복사해 두세요.
하는 일 (여러 번 실행해도 안전: 이미 적용된 곳은 건너뜀)  -- 대상은 static/index.html
  1. result-stage.css / result-stage.js 불러오기
  2. "카드 닫아줘" 같은 말을 하면 카드를 치우고 안내 문장을 말한다
  3. 서버 응답에 cards 배열이 있으면 코어 주변에 카드로 띄운다
"""
import argparse
import sys
from pathlib import Path


def read(path):
    raw = path.read_bytes()
    for enc in ("utf-8", "cp949"):
        try:
            return enc, raw.decode(enc)
        except UnicodeDecodeError:
            pass
    raise SystemExit(f"인코딩을 알 수 없어요: {path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    static = Path(a.root).resolve() / "src" / "main" / "resources" / "static"
    for need in ("js/result-stage.js", "css/result-stage.css"):
        if not (static / need).exists():
            print(f"static/{need} 가 없어요. 먼저 zip의 파일을 복사하세요.")
            return 1
    idx = static / "index.html"
    if not idx.exists():
        print("static/index.html 을 찾지 못했어요. 프로젝트 루트에서 실행하세요.")
        return 1

    steps = [
        ("스타일 불러오기", "css/result-stage.css", "</head>",
         '<link rel="stylesheet" href="/css/result-stage.css">\n</head>'),
        ("스크립트 불러오기", "js/result-stage.js", "</body>",
         '<script src="/js/result-stage.js"></script>\n</body>'),
        ("카드 치우기", "LumiCards.isClearRequest",
         "    if (!image && CROSS_CHECK_PHRASE.test(message) && message.length <= 30) {",
         "    // \"카드 닫아줘\", \"띄운 거 치워줘\" 처럼 결과 카드를 없애 달라는 말\n"
         "    if (!image && window.LumiCards && LumiCards.isClearRequest(message)) {\n"
         "      consoleInput.value = '';\n      appendLog('user', message);\n      LumiCards.clear();\n"
         "      const cleared = '결과 카드를 치웠어요.';\n      appendLog('lumi', cleared);\n"
         "      await enqueueSpeak(cleared);\n      return;\n    }\n"
         "    if (!image && CROSS_CHECK_PHRASE.test(message) && message.length <= 30) {"),
        ("응답 카드 띄우기", "lumi:card'",
         "      appendLinks(data.links);\n      await enqueueSpeak(data.reply);",
         "      appendLinks(data.links);\n"
         "      // 서버가 조회·검색 결과를 카드로 내려주면(cards 배열) 코어 주변에 띄운다.\n"
         "      if (Array.isArray(data.cards)) {\n"
         "        data.cards.forEach((card) => window.dispatchEvent(new CustomEvent('lumi:card', { detail: card })));\n      }\n"
         "      await enqueueSpeak(data.reply);"),
    ]
    enc, text = read(idx)
    out, notes = text, []
    for name, marker, anchor, new in steps:
        if marker in out:
            notes.append(f"  이미 적용됨: {name}")
        elif out.count(anchor) != 1:
            notes.append(f"  !! 못 찾음(수동 적용 필요): {name}")
        else:
            out = out.replace(anchor, new)
            notes.append(f"  적용: {name}")
    if out != text and not a.dry_run:
        idx.write_bytes(out.encode(enc))
    print("static/index.html")
    print("\n".join(notes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
