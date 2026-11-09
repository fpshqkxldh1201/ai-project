package com.jarvisst.Lumi.security;

import java.util.regex.Pattern;

/**
 * 외부(감정 분석 서버)에서 온 감정 분석 값을 프롬프트와 화면에 쓰기 전에 걸러낸다.
 *
 * (정보보안개론 5장 포맷 스트링 공격: "데이터의 형태에 대한 불명확한 정의" — 값이 어떤 모양일지 정해 두지 않고
 * 그대로 형식 문자열/명령에 넣으면, 값에 섞인 지시가 그대로 해석된다.)
 * 감정 이름은 짧은 단어여야 하므로 글자·숫자·공백과 _ - / & , . ( ) ' 만 허용하고(줄바꿈, [ ] { } < > % 등은 거부),
 * 아니면 'unknown'으로 바꾼다.
 * 수치는 정해진 범위로 자르고, NaN/무한대는 0으로 바꾼다.
 */
public final class EmotionSanitizer {

    private static final Pattern LABEL = Pattern.compile("^[\\p{L}\\p{N} _\\-/&,.()']{1,40}$");

    private EmotionSanitizer() {
    }

    public static String label(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return LABEL.matcher(trimmed).matches() ? trimmed : "unknown";
    }

    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(min, Math.min(max, value));
    }
}
