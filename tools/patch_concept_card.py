"""개념 카드(수식·그림) + 카드 언어 맞춤 패치 (ChatController, GeminiService 두 곳). 여러 번 실행해도 안전.

사용법 (Spring 프로젝트 루트, src 폴더가 보이는 곳에서):
  python tools/patch_concept_card.py --dry-run
  python tools/patch_concept_card.py
먼저 zip 의 새 파일/덮어쓸 파일을 같은 경로에 넣으세요.
"""
import argparse
from pathlib import Path

BASE = Path("src/main/java/com/jarvisst/Lumi")
CHAT = BASE / "controller/ChatController.java"
GEMINI = BASE / "service/GeminiService.java"


def read(path):
    raw = path.read_bytes()
    for enc in ("utf-8", "cp949"):
        try:
            return enc, raw.decode(enc)
        except UnicodeDecodeError:
            pass
    raise SystemExit(f"인코딩을 알 수 없어요: {path}")


def patch(path, steps, dry):
    if not path.exists():
        print(f"!! 파일 없음: {path}")
        return 1
    enc, text = read(path)
    out, bad = text, 0
    print(path.name)
    for name, marker, anchor, new, all_ in steps:
        if marker in out:
            print("  이미 적용됨:", name)
        elif out.count(anchor) < 1 or (not all_ and out.count(anchor) != 1):
            print("  !! 못 찾음(수동 적용 필요):", name)
            bad += 1
        else:
            out = out.replace(anchor, new)
            print("  적용:", name)
    if out != text and not dry:
        path.write_bytes(out.encode(enc))
    return bad


# ── ChatController ──
CHAT_STEPS = [
    ("카드 언어 시작", "ResultCardContext.begin(com.jarvisst.Lumi.service.CardLang.detect(",
     "            ResultCardContext.begin();",
     "            // 카드의 고정 문구는 사용자가 말한 언어를 따른다.\n"
     "            ResultCardContext.begin(com.jarvisst.Lumi.service.CardLang.detect(rawMessage));", False),
    ("ConceptEnricher 주입", "ConceptEnricher conceptEnricher;",
     "    private ResponseEntity<?> process(String rawMessage, String responseLanguage, ImageSanitizer.SanitizedImage image) {",
     "    /** 개념 카드에 수식·위키백과 그림을 붙이는 서비스(필드 주입). */\n"
     "    @org.springframework.beans.factory.annotation.Autowired\n"
     "    private com.jarvisst.Lumi.service.ConceptEnricher conceptEnricher;\n\n"
     "    private ResponseEntity<?> process(String rawMessage, String responseLanguage, ImageSanitizer.SanitizedImage image) {", False),
    ("수식 줄 분리 + 카드 마무리", "ConceptEnricher.Extracted extracted =",
     "        // 교차 검증용으로 사용자별 마지막 질문/답변",
     "        // 개념 설명: 답변 끝의 [[수식: …]] 줄을 카드의 수식으로 옮기고(말풍선·음성·번역에는 나가지 않게 지운다),\n"
     "        // 위키백과 그림·요약을 붙이고, 카드 문구를 사용자 언어에 맞춘다. 실패해도 원래 카드는 그대로 나간다.\n"
     "        if (!\"(대화 시작)\".equals(rawMessage.trim())) {\n"
     "            final String cardLang = com.jarvisst.Lumi.service.CardLang.detect(rawMessage);\n"
     "            com.jarvisst.Lumi.service.ConceptEnricher.Extracted extracted =\n"
     "                    com.jarvisst.Lumi.service.ConceptEnricher.extract(result.reply());\n"
     "            if (extracted.reply() != null && !extracted.reply().equals(result.reply())) {\n"
     "                result = new ChatResult(extracted.reply(), result.emotion(), result.links(), result.evidence(), result.toneLabel());\n"
     "            }\n"
     "            try {\n"
     "                cards = conceptEnricher.build(cards, rawMessage, result.reply(), extracted.formulas(), cardLang);\n"
     "            } catch (Exception ignored) {\n"
     "                // 카드 마무리에 실패해도 대화는 그대로 나간다.\n"
     "            }\n"
     "        }\n\n"
     "        // 교차 검증용으로 사용자별 마지막 질문/답변", False),
]

# ── GeminiService: 수식 줄 규칙을 시스템 프롬프트 뒤에 덧붙인다 ──
RULE_CONST = '''    /** 개념 설명 카드용 수식 줄 규칙. 서버가 [[수식: …]] 줄을 뽑아 카드의 수식으로 옮기고 답변에서는 지운다. */
    private static final String FORMULA_RULE = """

            [수식 카드 규칙]
            - 수학·과학·공학 개념을 설명할 때 핵심 공식이 있으면, 답변 맨 끝에 한 줄에 하나씩(최대 3개) 이 형식으로 적으세요:
              [[수식: LaTeX 수식 | 한 줄 설명]]   예) [[수식: \\\\int_a^b f(x)\\\\,dx = F(b)-F(a) | 미적분의 기본정리]]
            - 이 줄은 화면 카드에만 쓰이고 사용자에게는 보이지 않습니다. 본문에는 수식 기호를 쓰지 말고 말로 풀어 쓰세요(소리 내어 읽습니다).
            - 수식이 필요 없는 질문(일상 대화, 검색·일정·알림 등)에는 이 줄을 쓰지 마세요. LaTeX는 KaTeX가 지원하는 문법만 쓰세요.
            """;

'''
GEM_STEPS = [
    ("FORMULA_RULE 상수", "String FORMULA_RULE", "    private String currentTimeBlock() {", RULE_CONST + "    private String currentTimeBlock() {", False),
    ("시스템 프롬프트에 수식 규칙 덧붙이기", "SYSTEM_PROMPT + currentTimeBlock() + FORMULA_RULE",
     "SYSTEM_PROMPT + currentTimeBlock()", "SYSTEM_PROMPT + currentTimeBlock() + FORMULA_RULE", True),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    bad = 0
    bad += patch(CHAT, CHAT_STEPS, a.dry_run)
    bad += patch(GEMINI, GEM_STEPS, a.dry_run)
    print("완료" if not bad else f"수동 확인 필요 {bad}건")


main()
