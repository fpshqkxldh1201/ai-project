"""의도 라우터 연결 패치 (GeminiService 한 곳 + application.properties). 여러 번 실행해도 안전.

사용법 (Spring 프로젝트 루트, src 폴더가 보이는 곳에서):
  python tools/patch_intent_router.py --dry-run
  python tools/patch_intent_router.py
먼저 zip 의 IntentRouterService.java 를 같은 경로에 넣으세요.
"""
import argparse
import re
from pathlib import Path

GEMINI = Path("src/main/java/com/jarvisst/Lumi/service/GeminiService.java")
PROPS = Path("src/main/resources/application.properties")


def read(path):
    raw = path.read_bytes()
    for enc in ("utf-8", "cp949"):
        try:
            return enc, raw.decode(enc)
        except UnicodeDecodeError:
            pass
    raise SystemExit(f"인코딩을 알 수 없어요: {path}")


FIELD_ANCHOR = "    private LlmFallbackService llmFallback;\n"
FIELD_NEW = FIELD_ANCHOR + (
    "    /** 의도 라우터(날씨·웹검색·뉴스는 Gemini 없이 처리). 빈이 없거나 꺼져 있으면 건너뛴다. */\n"
    "    @org.springframework.beans.factory.annotation.Autowired(required = false)\n"
    "    private IntentRouterService intentRouter;\n"
)

CALL_ANCHOR = "        if (isGreetingTrigger) {\n            // 새 대화 시작 인사는"
CALL_NEW = (
    "        // 날씨·웹검색·뉴스는 작은 모델이 분류·요약하고 Gemini는 부르지 않는다. 애매하거나 실패하면 아래 Gemini 흐름으로 간다.\n"
    "        if (!isGreetingTrigger && image == null) {\n"
    "            ChatResult routed = tryIntentRouter(message, userParts, emotionFuture, toneLabel);\n"
    "            if (routed != null) {\n"
    "                return routed;\n"
    "            }\n"
    "        }\n"
) + CALL_ANCHOR

HELPER_ANCHOR = "    /** 응답에 시스템 프롬프트 표식이 섞여 있으면(유출) 응답 전체를 안내 문구로 바꾼다. */"
HELPER_NEW = '''    /**
     * 의도 라우터: 날씨·웹검색·뉴스로 확신하면 NAVER 검색 + 작은 모델 요약으로 답하고 카드를 1장 만든다(Gemini 호출 없음).
     * 분류가 애매하거나 검색·요약이 실패하면 null — 호출한 쪽이 Gemini로 계속 진행한다. 카드는 답변이 만들어진 뒤에만 추가한다.
     */
    private ChatResult tryIntentRouter(String message, List<GeminiPart> userParts,
                                       CompletableFuture<@EMOTION@> emotionFuture, String toneLabel) {
        IntentRouterService router = this.intentRouter;
        if (router == null || !router.isEnabled()) {
            return null;
        }
        long startedAt = System.nanoTime();
        try {
            IntentRouterService.Decision decision = router.classify(message).orElse(null);
            if (decision == null) {
                return null;
            }
            String query = inputSanitizer.cleanForStorage(decision.query(), 100);
            if (query == null || query.isBlank() || blockPersonLookup(query)) {
                return null; // 신상 검색 의심 등은 Gemini의 기존 거절 흐름에 맡긴다.
            }
            Map<String, Object> raw = decision.intent() == IntentRouterService.Intent.NEWS
                    ? naverSearchService.searchNews(query, 5)
                    : naverSearchService.search(query, 5);
            Map<String, Object> safe = sanitizeSearchResult(raw);
            if (safe.get("error") != null || !(safe.get("results") instanceof List<?> found) || found.isEmpty()) {
                log.info("[라우터] 검색 결과 없음 — Gemini로 넘김 (query={})", query);
                return null;
            }
            List<?> rawList = raw.get("results") instanceof List<?> l ? l : List.of();
            StringBuilder block = new StringBuilder();
            List<String> evidence = new ArrayList<>();
            for (int i = 0; i < found.size(); i++) {
                if (!(found.get(i) instanceof Map<?, ?> m)) {
                    continue;
                }
                String when = "";
                if (i < rawList.size() && rawList.get(i) instanceof Map<?, ?> r && r.get("pubDate") != null) {
                    when = " (" + inputSanitizer.sanitizeExternalText(String.valueOf(r.get("pubDate")), 40) + ")";
                }
                block.append(i + 1).append(". ").append(m.get("title")).append(when).append(" — ")
                        .append(m.get("description")).append('\\n');
                evidence.add("[Naver 검색] " + m.get("title") + " — " + m.get("description"));
            }
            List<String> hints = new ArrayList<>();
            for (GeminiPart p : userParts) {
                String t = p.text();
                if (t != null && (t.startsWith("[내부 감정분석") || t.startsWith("[답변 언어 지정"))) {
                    hints.add(t);
                }
            }
            String reply = router.summarize(SYSTEM_PROMPT + currentTimeBlock(), message, decision, block.toString(), hints)
                    .orElse(null);
            if (reply == null) {
                return null;
            }
            ResultCardContext.addAll(ResultCardBuilder.fromFunction("web_search", Map.of("query", query), safe));
            log.info("[라우터] {} 신뢰도={} → 직접 처리 완료 ({}ms, Gemini 호출 없음)", decision.intent(), decision.confidence(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return new ChatResult(guardReply(reply), resolveEmotion(emotionFuture), List.of(), joinEvidence(evidence), toneLabel);
        } catch (Exception e) {
            log.warn("[라우터] 처리 중 오류 — Gemini로 넘김: {}", e.toString());
            return null;
        }
    }

''' + HELPER_ANCHOR

PROP_BLOCK = """
# -- Intent router: weather / web search / news are answered without Gemini (small model + NAVER search).
# Uses the same OPENAI_API_KEY as the fallback model. Set router.enabled=false to turn it off.
router.enabled=true
router.min-confidence=0.8
"""

STEPS = [
    ("필드 선언", "IntentRouterService intentRouter;", FIELD_ANCHOR, FIELD_NEW),
    ("chat() 안에서 라우터 호출", "ChatResult routed = tryIntentRouter(", CALL_ANCHOR, CALL_NEW),
    ("tryIntentRouter 메서드", "private ChatResult tryIntentRouter(", HELPER_ANCHOR, HELPER_NEW),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if not GEMINI.exists():
        raise SystemExit("GeminiService.java 를 못 찾았어요. 프로젝트 루트에서 실행하세요.")
    if not Path("src/main/java/com/jarvisst/Lumi/service/IntentRouterService.java").exists():
        raise SystemExit("IntentRouterService.java 가 없어요. zip 의 파일을 먼저 넣으세요.")
    enc, text = read(GEMINI)
    # 감정 분석 결과 타입 이름은 프로젝트마다 다르다(ClipAnalysisResponse / EmotionResult 등) — 파일에서 읽어 맞춘다.
    m = re.search(r"CompletableFuture<(\w+)>\s+emotionFuture", text)
    emo = m.group(1) if m else "EmotionResult"
    print("  감정 결과 타입:", emo)
    out, bad = text, 0
    print(GEMINI.name)
    for name, marker, anchor, new in STEPS:
        if marker in out:
            print("  이미 적용됨:", name)
        elif out.count(anchor) != 1:
            print("  !! 못 찾음(수동 적용 필요):", name)
            bad += 1
        else:
            out = out.replace(anchor, new.replace("@EMOTION@", emo))
            print("  적용:", name)
    if out != text and not a.dry_run:
        GEMINI.write_bytes(out.encode(enc))
    if PROPS.exists():
        penc, ptext = read(PROPS)
        if "router.enabled" in ptext:
            print("application.properties: 이미 적용됨")
        else:
            print("application.properties: router 설정 추가")
            if not a.dry_run:
                PROPS.write_bytes((ptext.rstrip("\n") + "\n" + PROP_BLOCK).encode(penc))
    print("완료" if not bad else f"수동 확인 필요 {bad}건")


main()
