"""결과 카드(백엔드)를 ChatController·GeminiService에 연결하는 패치 스크립트.

사용법 (Lumi 프로젝트 루트, build.gradle 이 있는 곳에서):
  python tools/patch_result_cards_backend.py --dry-run
  python tools/patch_result_cards_backend.py

먼저 zip의 java 파일(ResultCard, ResultCardBuilder, ResultCardContext, ChatResponse)을 복사해 두세요.
여러 번 실행해도 안전합니다(이미 적용된 곳은 건너뜀). 끼울 자리를 못 찾으면 '못 찾음'이라고 알려 주니 그 부분만 직접 넣으면 됩니다.

하는 일
  ChatController : 요청 시작 때 카드 수집을 켜고, 함수 호출로 모인 카드를 응답의 cards 로 내려준다
                   (첫 인사 "(대화 시작)" 브리핑에서는 카드를 띄우지 않는다)
  GeminiService  : (1) 함수 실행 결과를 카드로 바꿔 모으고 (2) show_chart(그래프·표 띄우기) 함수를 추가하고
                   (3) 시스템 프롬프트에 수치 시각화 규칙을 한 줄 더한다
"""
import argparse
import sys
from pathlib import Path

BASE = Path("src/main/java/com/jarvisst/Lumi")


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
    out, notes, bad = text, [], 0
    for name, marker, anchor, new in steps:
        if marker in out:
            notes.append(f"  이미 적용됨: {name}")
        elif out.count(anchor) != 1:
            notes.append(f"  !! 못 찾음(수동 적용 필요): {name}")
            bad += 1
        else:
            out = out.replace(anchor, new)
            notes.append(f"  적용: {name}")
    if out != text and not dry:
        path.write_bytes(out.encode(enc))
    print(path.name)
    print("\n".join(notes))
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--root", default=".")
    a = ap.parse_args()
    root = Path(a.root).resolve()
    base = root / BASE
    for need in ("dto/chat/ResultCard.java", "service/ResultCardBuilder.java", "service/ResultCardContext.java"):
        if not (base / need).exists():
            print(f"{need} 가 없어요. 먼저 zip의 파일을 프로젝트에 복사하세요.")
            return 1
    ctrl, gem = base / "controller/ChatController.java", base / "service/GeminiService.java"
    if not ctrl.exists() or not gem.exists():
        print("ChatController.java / GeminiService.java 를 찾지 못했어요. 프로젝트 루트에서 실행하세요.")
        return 1

    bad = patch(ctrl, [
        ("import", "import com.jarvisst.Lumi.dto.chat.ResultCard;",
         "import java.util.Map;",
         "import com.jarvisst.Lumi.dto.chat.ResultCard;\nimport com.jarvisst.Lumi.service.ResultCardContext;\n\nimport java.util.List;\nimport java.util.Map;"),
        ("cards 변수", "List<ResultCard> cards;",
         "        ChatResult result;\n",
         "        ChatResult result;\n        List<ResultCard> cards;\n"),
        ("카드 수집", "ResultCardContext.begin();",
         "            result = geminiService.chat(rawMessage, responseLanguage, image);\n",
         "            ResultCardContext.begin();\n            try {\n"
         "                result = geminiService.chat(rawMessage, responseLanguage, image);\n"
         "                // 첫 인사 브리핑에서는 카드를 띄우지 않는다(사용자가 조회·검색했을 때만).\n"
         "                cards = \"(대화 시작)\".equals(rawMessage.trim()) ? List.of() : ResultCardContext.drain();\n"
         "            } finally {\n                ResultCardContext.clear();\n            }\n"),
        ("응답에 cards", "translatable, cards)",
         "result.links(), translatable));",
         "result.links(), translatable, cards));"),
    ], a.dry_run)

    bad += patch(gem, [
        ("함수 결과 → 카드", "ResultCardContext.addAll(",
         "        return new GeminiFunctionResponse(call.name(), result);",
         "        // 조회·검색 결과를 화면용 결과 카드로도 바꿔 모아 둔다(ChatController가 응답에 실어 보낸다).\n"
         "        ResultCardContext.addAll(ResultCardBuilder.fromFunction(call.name(), args, result));\n"
         "        return new GeminiFunctionResponse(call.name(), result);"),
        ("show_chart 실행", "case \"show_chart\"",
         "            case \"youtube_search\" -> handleYoutubeSearch(args, links);\n",
         "            case \"youtube_search\" -> handleYoutubeSearch(args, links);\n"
         "            case \"show_chart\" -> ResultCardBuilder.showChartResult(args);\n"),
        ("show_chart 선언", "ResultCardBuilder.SHOW_CHART_DECLARATION",
         "            )\n    ));\n\n    private final RestClient restClient;",
         "            ),\n            ResultCardBuilder.SHOW_CHART_DECLARATION\n    ));\n\n    private final RestClient restClient;"),
        ("프롬프트 규칙", "show_chart 함수로",
         "            - 내부 식별자(절대 출력 금지)",
         "            - 수치 시각화: 검색으로 확인한 비교·통계·추이 수치가 3개 이상이면 show_chart 함수로 그래프나 표를 "
         "화면에 띄우세요. 검색 결과에 실제로 나온 숫자만 쓰고 추정하거나 지어낸 수치는 절대 넣지 마세요. "
         "그래프를 띄운 뒤에는 수치를 다시 나열하지 말고 핵심만 짧게 말하세요.\n"
         "            - 내부 식별자(절대 출력 금지)"),
    ], a.dry_run)
    if bad:
        print(f"\n{bad}곳은 자동으로 못 끼웠어요. 위 설명대로 직접 넣어 주세요(안 끼운 곳이 있으면 카드·그래프 기능 일부만 동작).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
