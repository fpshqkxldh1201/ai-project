"""Lumi 결과 카드를 React 프론트(lumi-frontend)에 연결하는 패치 스크립트.

사용법 (Lumi 프로젝트 루트, lumi-frontend 폴더가 보이는 곳에서):
  python tools/patch_result_cards.py --dry-run   # 무엇이 바뀔지 보기
  python tools/patch_result_cards.py             # 적용

하는 일 (여러 번 실행해도 안전: 이미 적용된 곳은 건너뜀)
  1. CenterCore.jsx : ResultStage 불러오기 + 카드가 떠 있을 때 코어 클래스 처리
  2. LumiContext.jsx: (a) "카드 닫아줘" 같은 말을 하면 카드를 치우고
                      (b) 서버 응답에 cards 배열이 있으면 카드로 띄운다
먼저 zip의 components/ResultStage.jsx, resultCharts.jsx, ResultStage.css 를 복사해 두세요.
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


def patch(path, steps, dry):
    enc, text = read(path)
    out, notes = text, []
    for name, marker, anchor, new in steps:
        if marker in out:
            notes.append(f"  이미 적용됨: {name}")
            continue
        if out.count(anchor) != 1:
            notes.append(f"  !! 못 찾음(수동 적용 필요): {name}")
            continue
        out = out.replace(anchor, new)
        notes.append(f"  적용: {name}")
    if out != text and not dry:
        path.write_bytes(out.encode(enc))
    print(f"{path.name}")
    print("\n".join(notes))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    comp = Path(a.root).resolve() / "lumi-frontend" / "src" / "components"
    ctx = Path(a.root).resolve() / "lumi-frontend" / "src" / "context" / "LumiContext.jsx"
    for need in ("ResultStage.jsx", "resultCharts.jsx", "ResultStage.css"):
        if not (comp / need).exists():
            print(f"{need} 가 없어요. 먼저 zip의 파일을 lumi-frontend/src/components 에 복사하세요.")
            return 1
    cc = comp / "CenterCore.jsx"
    if not cc.exists() or not ctx.exists():
        print("CenterCore.jsx / LumiContext.jsx 를 찾지 못했어요. 프로젝트 루트에서 실행하세요.")
        return 1

    patch(cc, [
        ("import ResultStage", "import ResultStage",
         "import { createNeuronScene } from '../three/neuronScene.js'",
         "import { createNeuronScene } from '../three/neuronScene.js'\nimport ResultStage from './ResultStage.jsx'"),
        ("useState import", "useEffect, useRef, useState",
         "import { useEffect, useRef } from 'react'", "import { useEffect, useRef, useState } from 'react'"),
        ("hasCards 상태", "const [hasCards, setHasCards]",
         "  const activeRef = useRef(activeState.active)\n",
         "  const activeRef = useRef(activeState.active)\n  const [hasCards, setHasCards] = useState(false)\n"),
        ("section 클래스", "'center-hud' + (hasCards",
         '<section className="center-hud">', "<section className={'center-hud' + (hasCards ? ' has-cards' : '')}>"),
        ("ResultStage 배치", "<ResultStage",
         '        <div className="canvas-slot" ref={slotRef}></div>\n      </div>',
         '        <div className="canvas-slot" ref={slotRef}></div>\n      </div>\n      <ResultStage onOpenChange={setHasCards} />'),
    ], a.dry_run)

    patch(ctx, [
        ("카드 치우기 문구", "CARD_CLEAR_PHRASE",
         "const CROSS_CHECK_PHRASE =",
         "// \"카드 닫아줘\", \"띄운 거 치워줘\" 처럼 결과 카드를 없애 달라는 말(메모 삭제 등과 헷갈리지 않도록 '카드/결과/띄운'이 들어갈 때만).\n"
         "const CARD_CLEAR_PHRASE = /(카드|결과|띄운).{0,8}(닫|없애|지워|치워|숨겨|꺼)/\n\nconst CROSS_CHECK_PHRASE ="),
        ("카드 치우기 처리", "lumi:cards-clear",
         "    if (!image && CROSS_CHECK_PHRASE.test(message) && message.length <= 30) {",
         "    if (!image && CARD_CLEAR_PHRASE.test(message) && message.length <= 30) {\n"
         "      setInputValue('')\n      appendLog('user', message)\n"
         "      window.dispatchEvent(new CustomEvent('lumi:cards-clear'))\n"
         "      const done = '결과 카드를 치웠어요.'\n      appendLog('lumi', done)\n      await enqueueSpeak(done)\n      return\n    }\n"
         "    if (!image && CROSS_CHECK_PHRASE.test(message) && message.length <= 30) {"),
        ("응답 카드 띄우기", "lumi:card'",
         "      const lumiRowId = appendLog('lumi', data.reply, data.links)",
         "      const lumiRowId = appendLog('lumi', data.reply, data.links)\n"
         "      // 서버가 조회·검색 결과를 카드로 내려주면(cards 배열) 코어 주변에 띄운다.\n"
         "      if (Array.isArray(data.cards)) {\n"
         "        data.cards.forEach((card) => window.dispatchEvent(new CustomEvent('lumi:card', { detail: card })))\n      }"),
    ], a.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
