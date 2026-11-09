"""NAVER 검색 오류 + 일정 날짜 오류를 고치는 패치 (여러 번 실행해도 안전).

사용법 (Spring 프로젝트 루트, src 폴더가 보이는 곳에서):
  python tools/patch_naver_time.py --dry-run
  python tools/patch_naver_time.py

1. NaverSearchService.java : 응답이 text/plain 이어도 읽도록 문자열로 받아 JSON 직접 해석 + 실패 원인 로그
2. GeminiService.java      : 요청마다 '지금 날짜/시각/요일(한국 시간)'을 Gemini에 알려줌 → 일정 날짜가 맞게 저장됨
"""
import argparse
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
    out, bad = text, 0
    print(path.name)
    for name, marker, anchor, new, repl_all in steps:
        if marker in out:
            print(f"  이미 적용됨: {name}")
        elif out.count(anchor) < 1 or (not repl_all and out.count(anchor) != 1):
            print(f"  !! 못 찾음(수동 적용 필요): {name}")
            bad += 1
        else:
            out = out.replace(anchor, new)
            print(f"  적용: {name}")
    if out != text and not dry:
        path.write_bytes(out.encode(enc))
    return bad


NAVER_CALL_OLD = "            NaverSearchResponse response = restClient.get()"
NAVER_CALL_NEW = "            String rawBody = restClient.get()"
NAVER_BODY_OLD = "                    .body(NaverSearchResponse.class);\n"
NAVER_BODY_NEW = (
    "                    .body(String.class);\n"
    "            // NAVER API HUB는 JSON을 text/plain 으로 내려보낼 때가 있어 자동 변환이 실패한다 — 문자열로 받아 직접 해석.\n"
    "            NaverSearchResponse response = parseNaver(rawBody);\n"
)
NAVER_CATCH_OLD = '            return Map.of("error", "웹 검색 중 오류가 발생했습니다: " + e.getMessage());\n'
NAVER_CATCH_NEW = (
    '            org.slf4j.LoggerFactory.getLogger(NaverSearchService.class)\n'
    '                    .warn("[NAVER 검색] 실패 path={}, 원인={}", path, e.toString());\n'
    '            return Map.of("error", "웹 검색 중 오류가 발생했습니다: " + e.getMessage());\n'
)
NAVER_HELPER_ANCHOR = "    private String stripTags(String html) {"
NAVER_HELPER = '''    private static final tools.jackson.databind.ObjectMapper NAVER_MAPPER = new tools.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private static NaverSearchResponse parseNaver(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.Map<String, Object> root = NAVER_MAPPER.readValue(body,
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
        Object items = root.get("items");
        if (!(items instanceof List<?> list)) {
            return null;
        }
        List<NaverSearchResponse.Item> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof java.util.Map<?, ?> m) {
                out.add(new NaverSearchResponse.Item(str(m.get("title")), str(m.get("link")),
                        str(m.get("description")), str(m.get("pubDate"))));
            }
        }
        return new NaverSearchResponse(out);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

'''

TIME_HELPER_ANCHOR = "    private GeminiResponse callGemini(List<GeminiContent> contents) {"
TIME_HELPER = '''    /** 매 요청마다 '지금'을 알려줘서 "오늘/내일/12시" 같은 상대 표현이 올바른 날짜로 저장되게 한다. */
    private String currentTimeBlock() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
        String dow = now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN);
        return "\\n\\n[현재 시각 — 반드시 기준으로 사용] 지금은 한국 시간(KST) "
                + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")) + " (" + dow + ")입니다. "
                + "일정·알림의 '오늘', '내일', '모레', '다음 주 월요일', '12시' 같은 상대 표현은 반드시 이 시각을 기준으로 "
                + "계산해 startTime/remindAt(ISO-8601, 예: " + now.toLocalDate() + "T12:00:00)에 넣으세요. "
                + "과거 날짜로 추측하지 마세요. 시각만 말하고 이미 지난 시간이면 사용자에게 오늘인지 내일인지 확인하세요.";
    }

'''
SP_OLD = "GeminiPart.ofText(SYSTEM_PROMPT)"
SP_NEW = "GeminiPart.ofText(SYSTEM_PROMPT + currentTimeBlock())"
FB_OLD = "llmFallback.chat(SYSTEM_PROMPT,"
FB_NEW = "llmFallback.chat(SYSTEM_PROMPT + currentTimeBlock(),"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    base = Path("src/main/java/com/jarvisst/Lumi/service")
    bad = 0
    nv = base / "NaverSearchService.java"
    gm = base / "GeminiService.java"
    for p in (nv, gm):
        if not p.exists():
            raise SystemExit(f"파일이 없어요: {p} (프로젝트 루트에서 실행했는지 확인)")
    bad += patch(nv, [
        ("응답을 문자열로 받기(1/2)", "String rawBody", NAVER_CALL_OLD, NAVER_CALL_NEW, False),
        ("응답을 문자열로 받기(2/2)", "parseNaver(rawBody)", NAVER_BODY_OLD, NAVER_BODY_NEW, False),
        ("실패 원인 로그", "[NAVER 검색] 실패", NAVER_CATCH_OLD, NAVER_CATCH_NEW, False),
        ("JSON 해석 함수", "private static NaverSearchResponse parseNaver", NAVER_HELPER_ANCHOR,
         NAVER_HELPER + NAVER_HELPER_ANCHOR, False),
    ], a.dry_run)
    steps = [
        ("현재 시각 함수", "private String currentTimeBlock(", TIME_HELPER_ANCHOR, TIME_HELPER + TIME_HELPER_ANCHOR, False),
        ("Gemini 요청에 현재 시각 추가", "SYSTEM_PROMPT + currentTimeBlock())", SP_OLD, SP_NEW, True),
    ]
    _, gtext = read(gm)
    if "llmFallback.chat(SYSTEM_PROMPT," in gtext:
        steps.append(("예비 모델 요청에도 현재 시각 추가", "SYSTEM_PROMPT + currentTimeBlock(),", FB_OLD, FB_NEW, False))
    bad += patch(gm, steps, a.dry_run)
    print("\n완료" if not bad else f"\n수동 확인 필요 {bad}건")


main()
