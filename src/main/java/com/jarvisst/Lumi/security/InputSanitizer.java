package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 입력 검증 + 프롬프트 인젝션 방어용 공통 유틸(정보보안개론: 입력값 검증 / AI 보안).
 *
 * 1) 정규화: 제어문자·보이지 않는 문자(제로폭, 방향 전환 문자)를 제거해서 필터를 우회하거나
 *    화면/로그를 속이는 입력을 막는다.
 * 2) 태그 위조 무력화: 시스템이 Gemini에 덧붙이는 "[내부 감정분석]", "[답변 언어 지정]",
 *    "[현재 일정 정보]" 같은 힌트 태그를 사용자가 메시지에 흉내 내서 넣으면, 모델이 그것을
 *    시스템이 준 신뢰할 수 있는 정보로 오인할 수 있다 — 여는 대괄호를 전각으로 바꿔 무력화한다.
 * 3) 인젝션 시도 탐지: 알려진 공격 패턴을 찾아 "차단"이 아니라 "탐지 + 경고 힌트 + 로그"로
 *    처리한다(정상 대화를 오탐으로 막지 않기 위해). 실제 방어의 본체는 시스템 프롬프트의
 *    보안 원칙, 함수 인자 검증, 응답 유출 검사이고 이건 그 앞단의 감시 역할이다.
 * 4) 저장 데이터 정리: 일정 제목·메모 등 DB에 들어가는 문자열에서 HTML 태그를 제거한다
 *    (출력 시 textContent/React 이스케이프가 1차 방어, 이건 2차 방어).
 */
@Component
public class InputSanitizer {

    public static final int MAX_CHAT_LENGTH = 2000;

    private static final Pattern INVISIBLE_AND_CONTROL =
            Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");

    private static final Pattern FORGED_TAG = Pattern.compile(
            "\\[\\s*(?=(내부|답변\\s*언어|현재\\s*일정|시스템|보안|system|assistant|internal))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    /** 이름 -> 패턴. 이름은 로그/경고 힌트용 분류명이다. */
    private static final Map<String, Pattern> INJECTION_PATTERNS = new LinkedHashMap<>();

    static {
        int f = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
        INJECTION_PATTERNS.put("지시 무시 요구(한국어)", Pattern.compile(
                "(이전|위|앞|기존|모든|상기|지금까지).{0,12}(지시|명령|규칙|프롬프트|지침|설정).{0,12}(무시|잊어|무효|취소|따르지)"
                        + "|(무시|잊어).{0,12}(이전|위|앞|기존|모든|상기).{0,12}(지시|명령|규칙|프롬프트|지침|설정)", f));
        INJECTION_PATTERNS.put("지시 무시 요구(영어)", Pattern.compile(
                "(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+|your\\s+)*"
                        + "(previous|prior|above|earlier|system|original)?\\s*(instructions?|prompts?|rules?|guidelines?)", f));
        INJECTION_PATTERNS.put("시스템 프롬프트 유출 시도", Pattern.compile(
                "(시스템\\s*(프롬프트|지시|메시지)|system\\s*(prompt|message|instruction)s?|초기\\s*(지시|설정|프롬프트)|hidden\\s*instructions?)"
                        + ".{0,40}(보여|알려|출력|공개|말해|유출|복사|번역|요약|reveal|show|print|repeat|leak|dump|output|tell)"
                        + "|(보여|알려|출력|공개|말해|유출|복사|reveal|show|print|repeat|leak|dump|output|tell)"
                        + ".{0,40}(시스템\\s*(프롬프트|지시|메시지)|system\\s*(prompt|message|instruction)s?|초기\\s*(지시|설정|프롬프트)|hidden\\s*instructions?)", f));
        INJECTION_PATTERNS.put("역할 탈취/탈옥 시도", Pattern.compile(
                "(개발자|developer|DAN|디버그|debug|관리자|admin|god)\\s*(모드|mode)"
                        + "|you\\s+are\\s+now\\s+"
                        + "|지금부터\\s*(너|넌|당신)(는|은)?"
                        + "|jailbreak|탈옥"
                        + "|제한\\s*(없이|을\\s*해제)|no\\s+restrictions", f));
        INJECTION_PATTERNS.put("역할 위장(system:/assistant:)", Pattern.compile(
                "(^|\\n)\\s*(system|assistant|시스템|어시스턴트)\\s*[:：]", f));
    }

    /** 제어문자 제거 + 앞뒤 공백 제거 + 시스템 태그 위조 무력화. null이면 빈 문자열. */
    public String sanitizeUserText(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = INVISIBLE_AND_CONTROL.matcher(text).replaceAll("").trim();
        return FORGED_TAG.matcher(cleaned).replaceAll("［");
    }

    /** 인젝션 시도로 의심되면 분류명을, 아니면 null을 반환한다. */
    public String detectInjection(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Pattern> entry : INJECTION_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(text).find()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** DB에 저장될 문자열용: 제어문자·HTML 태그 제거 후 최대 길이로 자른다. */
    public String cleanForStorage(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String cleaned = INVISIBLE_AND_CONTROL.matcher(text).replaceAll(" ");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("").replace("<", "").replace(">", "").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    /** Gemini 함수 인자처럼 타입이 보장되지 않는 값에서 안전하게 문자열만 꺼낸다. */
    public String asString(Object value) {
        return (value instanceof String s) ? s : null;
    }

    /** 외부(웹 검색 결과 등) 텍스트를 모델에 넘기기 전에 길이를 제한하고 태그 위조를 무력화한다. */
    public String sanitizeExternalText(String text, int maxLength) {
        String cleaned = cleanForStorage(text, maxLength);
        return cleaned == null ? "" : FORGED_TAG.matcher(cleaned).replaceAll("［");
    }
}
