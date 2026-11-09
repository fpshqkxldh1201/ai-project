package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.chat.ResultCard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개념 설명 카드를 "자세하게" 만든다: 답변 끝의 [[수식: LaTeX | 설명]] 줄을 카드의 수식으로 옮기고,
 * 위키백과의 그림·요약을 붙이고, 카드 문구를 사용자 언어에 맞춘다.
 *
 * 수식은 모델이 쓴 글이므로 신뢰하지 않는다: 개수·길이를 제한하고, 파일·링크·매크로 정의 명령이 들어 있으면 버린다.
 * 프론트는 KaTeX(trust 꺼짐)로 그리므로 어떤 경우에도 HTML이나 스크립트로 해석되지 않는다.
 */
@Service
public class ConceptEnricher {

    /** 모델이 답변 끝에 적는 수식 줄: [[수식: \int_a^b f(x)dx | 정적분]] (설명은 선택) */
    private static final Pattern MARK = Pattern.compile("\\[\\[\\s*(?:수식|formula)\\s*[:：]\\s*(.{1,400}?)\\s*]]",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS = Pattern.compile(
            "\\\\(href|url|includegraphics|input|include|write|read|def|edef|gdef|xdef|let|newcommand|renewcommand|"
                    + "providecommand|catcode|csname|html\\w*|style|class|id|toks|expandafter|loop|openin|openout|immediate)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_FORMULAS = 3;
    private static final int MAX_TEX = 240;

    public record Extracted(String reply, List<ResultCard.Formula> formulas) {
    }

    private final ConceptMediaService media;

    /** 카드 문구를 사용자 언어로 옮기는 번역기(없으면 번역 단계를 건너뛴다). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TranslationService translator;

    /** 테스트에서 번역기를 끼워 넣는 용도. */
    interface CardTranslator {
        String translateTo(String text, String lang);
    }

    private CardTranslator cardTranslator;

    void setCardTranslator(CardTranslator t) {
        this.cardTranslator = t;
    }

    private String translate(String text, String lang) {
        if (cardTranslator != null) {
            return cardTranslator.translateTo(text, lang);
        }
        return translator == null ? null : translator.translateTo(text, lang);
    }

    public ConceptEnricher(ConceptMediaService media) {
        this.media = media;
    }

    /** 답변에서 수식 줄을 뽑고, 수식 줄을 뺀 답변을 돌려준다. 수식 줄이 없으면 reply는 그대로. */
    public static Extracted extract(String reply) {
        if (reply == null) {
            return new Extracted(null, List.of());
        }
        List<ResultCard.Formula> out = new ArrayList<>();
        Matcher m = MARK.matcher(reply);
        while (m.find()) {
            if (out.size() >= MAX_FORMULAS) {
                continue;
            }
            String body = m.group(1).strip();
            String tex = body, caption = null;
            int bar = lastTopLevelBar(body);
            if (bar > 0) {
                tex = body.substring(0, bar).strip();
                caption = body.substring(bar + 1).strip();
            }
            if (validTex(tex)) {
                out.add(new ResultCard.Formula(tex, caption == null || caption.isBlank() ? null : clip(caption, 60)));
            }
        }
        if (!MARK.matcher(reply).find()) {
            return new Extracted(reply, List.of());
        }
        String cleaned = MARK.matcher(reply).replaceAll("").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        return new Extracted(cleaned, out);
    }

    /** 수식 안의 \left| 나 절댓값 | 와 구분하기 위해, 중괄호 밖에 있고 앞뒤가 공백인 마지막 '|'만 설명 구분자로 본다. */
    private static int lastTopLevelBar(String s) {
        int depth = 0, found = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == '|' && depth == 0 && i > 0 && i + 1 < s.length()
                    && Character.isWhitespace(s.charAt(i - 1)) && Character.isWhitespace(s.charAt(i + 1))) {
                found = i;
            }
        }
        return found;
    }

    static boolean validTex(String tex) {
        if (tex == null || tex.isBlank() || tex.length() > MAX_TEX || DANGEROUS.matcher(tex).find()
                || tex.matches("(?s).*[\\p{Cntrl}&&[^\\t]].*")) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < tex.length(); i++) {
            char c = tex.charAt(i);
            if (c == '\\') {
                i++; // 이스케이프된 문자(\{ 등)는 건너뛴다
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max - 1).strip() + "…" : t;
    }

    /**
     * 카드 목록을 마무리한다: 언어 표시, 개념 카드에 수식·그림·요약 붙이기. 개념 카드가 없는데 수식이 있으면
     * (질문 문구가 패턴에 안 맞은 경우) 답변으로 개념 카드를 만든다. 실패해도 원래 카드를 그대로 돌려준다.
     */
    public List<ResultCard> build(List<ResultCard> cards, String userMessage, String reply,
                                  List<ResultCard.Formula> formulas, String lang) {
        // 답변이 사용자 입력과 다른 언어로 나온 경우(예: 한국어로 물었는데 영어 답변 모드)에도 카드는 입력 언어로 보여 준다.
        boolean mismatch = reply != null && !reply.isBlank() && !lang.equals(CardLang.detect(reply));
        List<ResultCard> list = new ArrayList<>(cards == null ? List.of() : cards);
        boolean hasExplain = list.stream().anyMatch(c -> "explain".equals(c.type()));
        if (!hasExplain && list.isEmpty() && formulas != null && !formulas.isEmpty()) {
            ResultCard forced = ResultCardBuilder.explainCardForced(userMessage, reply);
            if (forced != null) {
                list.add(forced);
            }
        }
        List<ResultCard> out = new ArrayList<>();
        for (ResultCard c : list) {
            ResultCard card = c.withLang(lang);
            if ("explain".equals(card.type())) {
                List<ResultCard.Formula> fs = formulas;
                if (mismatch) {
                    try {
                        Object[] localized = localize(card, formulas, lang);
                        card = (ResultCard) localized[0];
                        @SuppressWarnings("unchecked")
                        List<ResultCard.Formula> lf = (List<ResultCard.Formula>) localized[1];
                        fs = lf;
                    } catch (Exception e) {
                        // 번역이 안 되면 원문 그대로 둔다.
                    }
                }
                try {
                    card = decorate(card, fs, lang);
                } catch (Exception e) {
                    // 그림·요약이 없어도 카드는 완성된다.
                }
            }
            out.add(card);
        }
        return out;
    }

    private static final Pattern TAGGED = Pattern.compile("^\\[(\\d+)]\\s*(.*)$");

    /** 카드의 요약 문장·요점·수식 설명을 한 번의 번역 호출로 사용자 언어로 옮긴다. 줄 수가 맞지 않으면 원문 유지. */
    private Object[] localize(ResultCard card, List<ResultCard.Formula> formulas, String lang) {
        List<String> src = new ArrayList<>();
        if (card.lead() != null && !card.lead().isBlank()) {
            src.add(card.lead());
        }
        int pointsFrom = src.size();
        List<String> points = card.points() == null ? List.of() : card.points();
        src.addAll(points);
        int capFrom = src.size();
        List<ResultCard.Formula> fl = formulas == null ? List.of() : formulas;
        for (ResultCard.Formula f : fl) {
            src.add(f.caption() == null ? "" : f.caption());
        }
        if (src.stream().allMatch(String::isBlank)) {
            return new Object[]{card, formulas};
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < src.size(); i++) {
            in.append('[').append(i + 1).append("] ").append(src.get(i).replace('\n', ' ')).append('\n');
        }
        String out;
        try {
            final String text = in.toString();
            out = CompletableFuture.supplyAsync(() -> translate(text, lang)).orTimeout(8, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            out = null;
        }
        if (out == null) {
            return new Object[]{card, formulas};
        }
        String[] res = new String[src.size()];
        for (String line : out.split("\\R")) {
            Matcher m = TAGGED.matcher(line.strip());
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1)) - 1;
                if (idx >= 0 && idx < res.length) {
                    res[idx] = m.group(2).strip();
                }
            }
        }
        for (int i = 0; i < res.length; i++) {
            if (res[i] == null && !src.get(i).isBlank()) {
                return new Object[]{card, formulas}; // 한 줄이라도 빠지면 섞이지 않게 통째로 원문 유지
            }
        }
        String lead = pointsFrom == 1 ? clip(res[0], 160) : card.lead();
        List<String> np = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            np.add(clip(res[pointsFrom + i], 200));
        }
        List<ResultCard.Formula> nf = new ArrayList<>();
        for (int i = 0; i < fl.size(); i++) {
            String cap = res[capFrom + i];
            nf.add(new ResultCard.Formula(fl.get(i).tex(), cap == null || cap.isBlank() ? null : clip(cap, 120)));
        }
        return new Object[]{card.withTexts(lead, np.isEmpty() ? card.points() : np), nf};
    }

    private ResultCard decorate(ResultCard card, List<ResultCard.Formula> formulas, String lang) {
        ResultCard.Image image = null;
        String detail = null;
        Optional<ConceptMediaService.WikiInfo> info = Optional.empty();
        try {
            info = CompletableFuture.supplyAsync(() -> media.lookup(card.title(), lang))
                    .orTimeout(3, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            // 시간 초과·조회 실패 → 그림 없이 진행
        }
        if (info.isPresent()) {
            ConceptMediaService.WikiInfo w = info.get();
            detail = clip(w.extract(), 420);
            if (w.thumbUrl() != null) {
                String proxied = media.register(w.thumbUrl());
                if (proxied != null) {
                    image = new ResultCard.Image(proxied, clip(w.title(), 60),
                            CardLang.t("explain.imgcredit", lang), w.pageUrl());
                }
            } else if (w.pageUrl() != null) {
                image = null;
            }
            if (image == null && w.pageUrl() != null) {
                // 그림이 없어도 출처 링크는 남긴다.
                image = new ResultCard.Image(null, clip(w.title(), 60), CardLang.t("explain.wiki", lang), w.pageUrl());
            }
        }
        return card.withConcept(formulas, image, detail);
    }
}
