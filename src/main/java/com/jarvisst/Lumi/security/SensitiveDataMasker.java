package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 민감정보 분류·가림 (정보보안개론 사회공학 — "정보의 분류와 관리가 허술한 조직에서는 정보가 쉽게 노출된다",
 * "정보의 가치를 모르는 사람은 자신도 모르게 유출한다").
 *
 * 사용자가 대화창에 주민등록번호, 카드번호, API 키 같은 값을 그대로 붙여 넣으면 그 값이 외부 AI 서버(Gemini)로
 * 전송되고, 서버 메모리(교차 검증용 직전 질문)에도 남는다. 값이 서버를 떠나거나 남기 전에 자리표시자로 바꾼다.
 */
@Component
public class SensitiveDataMasker {

    public static final String PLACEHOLDER = "[가려진 민감정보]";

    public record Result(String text, int masked) {
    }

    /** 주민등록번호/외국인등록번호: 앞 6자리(생년월일) + 성별 숫자 1~8 + 6자리. 하이픈은 있어도 없어도 됨. */
    private static final Pattern RRN = Pattern.compile(
            "(?<!\\d)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])[- ]?[1-8]\\d{6}(?!\\d)");

    /** 13~19자리 카드번호 후보(공백·하이픈 허용). 실제 카드번호 검산(Luhn)을 통과해야 가린다. */
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");

    /** 대표적인 비밀 키 모양: Google API 키, OpenAI/Anthropic 계열, GitHub 토큰, AWS 액세스 키, 개인 키 블록. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "AIza[0-9A-Za-z_\\-]{35}"
                    + "|sk-[A-Za-z0-9_\\-]{20,}"
                    + "|gh[pousr]_[A-Za-z0-9]{30,}"
                    + "|AKIA[0-9A-Z]{16}"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----");

    public Result mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        int count = 0;
        String out = text;

        Matcher m = SECRET_KEY.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            count++;
            m.appendReplacement(sb, Matcher.quoteReplacement(PLACEHOLDER));
        }
        m.appendTail(sb);
        out = sb.toString();

        m = RRN.matcher(out);
        sb = new StringBuilder();
        while (m.find()) {
            count++;
            m.appendReplacement(sb, Matcher.quoteReplacement(PLACEHOLDER));
        }
        m.appendTail(sb);
        out = sb.toString();

        m = CARD.matcher(out);
        sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
                count++;
                m.appendReplacement(sb, Matcher.quoteReplacement(PLACEHOLDER));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        out = sb.toString();

        return new Result(out, count);
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }
}
