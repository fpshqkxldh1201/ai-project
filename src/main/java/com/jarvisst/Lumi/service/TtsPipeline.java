package com.jarvisst.Lumi.service;

import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * Fish Audio 호출 앞뒤의 순수 로직(읽을 글 정리 → 짧게 나누기 → 재시도). 네트워크와 분리해 두어서 단독으로 시험할 수 있다.
 * 브라우저 내장 목소리로 새지 않도록, 한 번의 실패로 포기하지 않고 일시 오류(429·5xx·시간 초과)는 다시 시도한다.
 */
final class TtsPipeline {

    /** Fish Audio가 준 HTTP 오류(status 0이면 연결·시간 초과 같은 네트워크 오류). */
    static final class HttpFailure extends Exception {
        final int status;
        final String body;

        HttpFailure(int status, String body, Throwable cause) {
            super("HTTP " + status + (body == null || body.isBlank() ? "" : " — " + body), cause);
            this.status = status;
            this.body = body;
        }

        boolean retryable() {
            return status == 0 || status == 429 || status >= 500;
        }
    }

    interface Poster {
        byte[] post(String chunk) throws HttpFailure;
    }

    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern FORMULA_MARK = Pattern.compile("\\[\\[\\s*(수식|formula)\\s*:.*?]]", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY_MATH = Pattern.compile("\\$\\$.*?\\$\\$|\\\\\\[.*?\\\\]", Pattern.DOTALL);
    private static final Pattern INLINE_MATH = Pattern.compile("\\$[^$\\n]{1,120}\\$|\\\\\\([^\\n]{1,120}?\\\\\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]{1,80})]\\((?:https?://)[^)]*\\)");
    private static final Pattern MD_MARKS = Pattern.compile("(?m)^\\s{0,3}(#{1,6}|>|[-*+•·])\\s+|[*_`~]{1,3}");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");

    private TtsPipeline() {
    }

    /** 소리 내어 읽기 어려운 것(코드·수식·주소·마크다운 기호)을 걷어낸다. 걷어낼 게 없으면 원문 그대로. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String t = CODE_BLOCK.matcher(text).replaceAll(" ");
        t = FORMULA_MARK.matcher(t).replaceAll(" ");
        t = DISPLAY_MATH.matcher(t).replaceAll(" ");
        // 가격($5 and $10)을 수식으로 오해하지 않도록, 수식 기호가 들어 있는 것만 지운다.
        t = INLINE_MATH.matcher(t).replaceAll(m -> {
            String g = m.group();
            return g.matches("(?s).*[\\\\^_{}=].*") ? " " : java.util.regex.Matcher.quoteReplacement(g);
        });
        t = MD_LINK.matcher(t).replaceAll("$1");
        t = URL.matcher(t).replaceAll(" ");
        t = MD_MARKS.matcher(t).replaceAll("");
        t = SPACES.matcher(t).replaceAll(" ");
        t = t.replaceAll("\\n{3,}", "\n\n").strip();
        return t;
    }

    /** 문장 경계에서 max자 안팎으로 나눈다. 한 문장이 max보다 길면 공백에서 자른다. */
    static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?。？！…])\\s+|\\n+")) {
            String s = sentence.strip();
            if (s.isEmpty()) {
                continue;
            }
            while (s.length() > max) {
                int cut = s.lastIndexOf(' ', max);
                if (cut < max / 2) {
                    cut = max;
                }
                flushIfNeeded(out, cur, cut, max);
                cur.append(s, 0, cut).append(' ');
                s = s.substring(cut).strip();
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            if (cur.length() + s.length() + 1 > max && cur.length() > 0) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(s).append(' ');
        }
        if (cur.toString().isBlank() == false) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    private static void flushIfNeeded(List<String> out, StringBuilder cur, int add, int max) {
        if (cur.length() > 0 && cur.length() + add > max) {
            out.add(cur.toString().strip());
            cur.setLength(0);
        }
    }

    /**
     * 정리 → 나누기 → 조각마다 최대 attempts번 시도. 일시 오류가 아니면(키 오류·크레딧 소진·잘못된 요청) 바로 멈춘다.
     * 첫 조각부터 실패하면 null, 중간부터 실패하면 거기까지 이어 붙인 소리를 돌려준다(아예 무음보다 낫다).
     */
    static byte[] run(String text, int chunkMax, int attempts, Poster poster, LongConsumer sleeper, Logger log) {
        String cleaned = clean(text);
        if (cleaned.isBlank()) {
            log.info("[TTS] 읽을 글이 없어서(수식·주소만 있음) 건너뜀");
            return null;
        }
        List<String> parts = chunks(cleaned, chunkMax);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i = 0; i < parts.size(); i++) {
            byte[] audio = null;
            HttpFailure last = null;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    audio = poster.post(parts.get(i));
                    if (audio != null && audio.length > 0) {
                        break;
                    }
                    last = new HttpFailure(200, "빈 응답", null);
                } catch (HttpFailure e) {
                    last = e;
                    if (!e.retryable()) {
                        break;
                    }
                }
                if (attempt < attempts) {
                    log.warn("[TTS] 조각 {}/{} 일시 오류({}) — 재시도 {}/{}", i + 1, parts.size(), last.getMessage(), attempt, attempts - 1);
                    sleeper.accept(600L * attempt);
                }
            }
            if (audio == null || audio.length == 0) {
                log.warn("[TTS] Fish Audio 실패 — 조각 {}/{} ({}자), 원인: {}", i + 1, parts.size(),
                        parts.get(i).length(), last == null ? "알 수 없음" : last.getMessage());
                return all.size() > 0 ? all.toByteArray() : null;
            }
            all.writeBytes(audio);
        }
        return all.toByteArray();
    }
}
