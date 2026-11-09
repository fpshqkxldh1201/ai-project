package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.chat.ResultCard;
import com.jarvisst.Lumi.dto.gemini.GeminiFunctionDeclaration;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 함수 호출 결과(일정·리마인더·검색)와 show_chart 인자를 화면용 결과 카드로 바꾼다.
 *
 * 보안: 일정·리마인더는 우리 DB에서 나온 값이지만, 검색 결과와 show_chart 인자는 모델(=사용자 입력과
 * 외부 웹 글의 영향을 받음)이 만든 값이라 신뢰하지 않는다. 개수·길이·숫자 범위를 서버에서 다시
 * 검증하고, 문제가 있으면 카드를 만들지 않는다. 카드의 글은 프론트가 텍스트로만 그린다(HTML 해석 없음).
 */
public final class ResultCardBuilder {

    private static final int MAX_TITLE = 40;
    private static final int MAX_LABEL = 16;
    private static final int MAX_CELL = 30;
    private static final double MAX_ABS = 1e12;

    private ResultCardBuilder() {
    }

    /** Gemini에 알려줄 그래프·표 띄우기 함수 선언. */
    public static final GeminiFunctionDeclaration SHOW_CHART_DECLARATION = new GeminiFunctionDeclaration(
            "show_chart",
            "검색이나 조회로 확인한 수치를 화면에 그래프나 표로 띄웁니다. 비교·통계·추이처럼 숫자가 3개 이상이고 "
                    + "그림으로 보는 편이 더 직관적일 때만 사용하세요. 검색 결과에 실제로 나온 숫자만 넣고, "
                    + "추정하거나 지어낸 수치는 절대 넣지 마세요. kind: pie(비율), bar(항목 비교), line(시간에 따른 추이), "
                    + "candle(주가 시가·고가·저가·종가), table(표).",
            Map.of(
                    "type", "OBJECT",
                    "properties", Map.ofEntries(
                            Map.entry("kind", Map.of("type", "STRING", "description", "pie | bar | line | candle | table")),
                            Map.entry("title", Map.of("type", "STRING", "description", "카드 제목(40자 이내)")),
                            Map.entry("unit", Map.of("type", "STRING", "description", "값의 단위(예: 건, %, 원). 선택")),
                            Map.entry("lead", Map.of("type", "STRING", "description", "한 줄 요약(80자 이내). 선택")),
                            Map.entry("labels", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"),
                                    "description", "pie/bar/line의 항목 이름 목록")),
                            Map.entry("values", Map.of("type", "ARRAY", "items", Map.of("type", "NUMBER"),
                                    "description", "pie/bar/line의 값 목록(labels와 같은 개수)")),
                            Map.entry("open", Map.of("type", "ARRAY", "items", Map.of("type", "NUMBER"), "description", "candle 시가 목록")),
                            Map.entry("high", Map.of("type", "ARRAY", "items", Map.of("type", "NUMBER"), "description", "candle 고가 목록")),
                            Map.entry("low", Map.of("type", "ARRAY", "items", Map.of("type", "NUMBER"), "description", "candle 저가 목록")),
                            Map.entry("close", Map.of("type", "ARRAY", "items", Map.of("type", "NUMBER"), "description", "candle 종가 목록")),
                            Map.entry("volume", Map.of("type", "ARRAY", "items", Map.of("type", "NUMBER"), "description", "candle 거래량 목록(선택)")),
                            Map.entry("columns", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"), "description", "table의 열 이름(2~4개)")),
                            Map.entry("rows", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"),
                                    "description", "table의 행. 각 행을 '셀1|셀2|셀3' 한 문자열로(최대 8행)"))
                    ),
                    "required", List.of("kind", "title")
            )
    );

    /** show_chart 호출에 대한 모델용 응답(카드가 만들어졌는지 알려 준다). */
    public static Map<String, Object> showChartResult(Map<String, Object> args) {
        Parsed parsed = parseChart(args);
        if (parsed.card() == null) {
            return Map.of("shown", false, "error", parsed.error());
        }
        return Map.of("shown", true, "notice", "그래프/표가 화면에 표시되었습니다. 수치를 다시 나열하지 말고 핵심만 짧게 말하세요.");
    }

    /** 함수 하나의 실행 결과에서 카드를 만든다. 만들 게 없으면 빈 목록. */
    public static List<ResultCard> fromFunction(String name, Map<String, Object> args, Map<String, Object> result) {
        if (name == null || result == null || result.containsKey("error")) {
            return List.of();
        }
        ResultCard card = switch (name) {
            case "get_schedules" -> scheduleCard(result.get("schedules"));
            case "get_reminders" -> reminderCard(result.get("reminders"));
            case "web_search" -> naverCard(args, result.get("results"));
            case "google_search" -> result.get("results") != null ? naverCard(args, result.get("results")) : googleCard(args, result);
            case "show_chart" -> parseChart(args).card();
            default -> null;
        };
        return card == null ? List.of() : List.of(card);
    }

    // ───────────────────────── 일정 ─────────────────────────

    private record Sch(String title, LocalDateTime start, LocalDateTime end) {
    }

    private static ResultCard scheduleCard(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Sch> all = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                LocalDateTime st = parseTime(m.get("startTime"));
                if (st == null) {
                    continue;
                }
                LocalDateTime en = parseTime(m.get("endTime"));
                if (en == null || !en.isAfter(st)) {
                    en = st.plusHours(1);
                }
                all.add(new Sch(clip(str(m.get("title")), 30), st, en));
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        all.sort(Comparator.comparing(Sch::start));

        Map<LocalDate, List<Sch>> byDay = new LinkedHashMap<>();
        for (Sch s : all) {
            byDay.computeIfAbsent(s.start().toLocalDate(), k -> new ArrayList<>()).add(s);
        }
        LocalDate day = null;
        for (Map.Entry<LocalDate, List<Sch>> e : byDay.entrySet()) {
            if (anyConflict(e.getValue())) {
                day = e.getKey();
                break;
            }
        }
        boolean conflict = day != null;
        if (day == null) {
            day = byDay.keySet().iterator().next();
        }
        List<Sch> dayItems = byDay.get(day);
        List<Sch> shown = new ArrayList<>();
        for (Sch s : dayItems) {
            if (!conflict || overlapsAny(s, dayItems)) {
                shown.add(s);
            }
            if (shown.size() >= 4) {
                break;
            }
        }

        int from = shown.stream().mapToInt(s -> s.start().getHour()).min().orElse(9);
        int to = shown.stream().mapToInt(s -> endHour(s)).max().orElse(from + 2);
        if (to - from > 12) {
            int limit = from + 12;
            shown = new ArrayList<>(shown.stream().filter(s -> s.start().getHour() < limit).toList());
            to = limit;
        }
        if (to - from < 2) {
            to = Math.min(24, from + 2);
            from = to - 2;
        }
        final int windowEnd = to;

        List<ResultCard.Timeline.Item> items = new ArrayList<>();
        for (Sch s : shown) {
            items.add(new ResultCard.Timeline.Item(s.title(), hhmm(s.start()), endLabel(s, windowEnd),
                    conflict && overlapsAny(s, dayItems) ? Boolean.TRUE : null));
        }

        String lead;
        if (conflict) {
            lead = conflictLead(shown);
        } else {
            lead = CardLang.t("sch.lead", L(), dayLabel(day), dayItems.size());
        }
        String note = all.size() == shown.size()
                ? CardLang.t("sch.note.all", L(), all.size())
                : CardLang.t(conflict ? "sch.note.conflict" : "sch.note.near", L(), all.size(), shown.size());
        return new ResultCard(newId(), "schedule", CardLang.t(conflict ? "sch.title.conflict" : "sch.title", L(), dayLabel(day)), lead,
                null, null, new ResultCard.Timeline(from, to, items), null, null, note);
    }

    private static boolean anyConflict(List<Sch> day) {
        for (Sch s : day) {
            if (overlapsAny(s, day)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAny(Sch a, List<Sch> day) {
        for (Sch b : day) {
            if (a != b && a.start().isBefore(b.end()) && b.start().isBefore(a.end())) {
                return true;
            }
        }
        return false;
    }

    private static String conflictLead(List<Sch> shown) {
        for (int i = 0; i < shown.size(); i++) {
            for (int j = i + 1; j < shown.size(); j++) {
                Sch a = shown.get(i), b = shown.get(j);
                if (a.start().isBefore(b.end()) && b.start().isBefore(a.end())) {
                    LocalDateTime os = a.start().isAfter(b.start()) ? a.start() : b.start();
                    LocalDateTime oe = a.end().isBefore(b.end()) ? a.end() : b.end();
                    long minutes = java.time.Duration.between(os, oe).toMinutes();
                    String who = shown.size() == 2 ? CardLang.t("sch.who.two", L()) : CardLang.t("sch.who.n", L(), shown.size());
                    return CardLang.t("sch.overlap", L(), hhmm(os), hhmm(oe), who, minutes);
                }
            }
        }
        return CardLang.t("sch.conflict.some", L());
    }

    private static int endHour(Sch s) {
        if (!s.end().toLocalDate().equals(s.start().toLocalDate())) {
            return 24;
        }
        return s.end().getHour() + (s.end().getMinute() > 0 ? 1 : 0);
    }

    private static String endLabel(Sch s, int windowEnd) {
        if (!s.end().toLocalDate().equals(s.start().toLocalDate()) || endHour(s) > windowEnd) {
            return windowEnd + ":00";
        }
        return hhmm(s.end());
    }

    private static String hhmm(LocalDateTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String dayLabel(LocalDate day) {
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return CardLang.t("day.today", L());
        }
        if (day.equals(today.plusDays(1))) {
            return CardLang.t("day.tomorrow", L());
        }
        return day.getMonthValue() + "/" + day.getDayOfMonth();
    }

    // ───────────────────────── 리마인더 ─────────────────────────

    private static ResultCard reminderCard(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        record R(LocalDateTime at, String content) {
        }
        List<R> rs = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                LocalDateTime at = parseTime(m.get("remindAt"));
                String content = clip(str(m.get("content")), 50);
                if (at != null && !content.isBlank()) {
                    rs.add(new R(at, content));
                }
            }
        }
        if (rs.isEmpty()) {
            return null;
        }
        rs.sort(Comparator.comparing(R::at));
        List<String> points = new ArrayList<>();
        for (R r : rs) {
            if (points.size() >= 5) {
                break;
            }
            points.add(dayLabel(r.at().toLocalDate()) + " " + hhmm(r.at()) + "  " + r.content());
        }
        String note = rs.size() > points.size() ? CardLang.t("rem.more", L(), rs.size() - points.size()) : null;
        return new ResultCard(newId(), "reminder", CardLang.t("rem.title", L()), CardLang.t("rem.lead", L(), rs.size()),
                points, null, null, null, null, note);
    }

    // ───────────────────────── 검색 ─────────────────────────

    private static ResultCard naverCard(Map<String, Object> args, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> points = new ArrayList<>();
        Set<String> hosts = new LinkedHashSet<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                String desc = clip(str(m.get("description")), 90);
                if (!desc.isBlank() && points.size() < 3) {
                    points.add(desc);
                }
                String host = hostOf(str(m.get("link")));
                if (!host.isBlank() && hosts.size() < 3) {
                    hosts.add(host);
                }
            }
        }
        if (points.isEmpty()) {
            return null;
        }
        String query = clip(str(args == null ? null : args.get("query")), MAX_TITLE);
        return new ResultCard(newId(), "search", query.isBlank() ? CardLang.t("search.title", L()) : query,
                CardLang.t("search.lead", L(), list.size()), points,
                hosts.isEmpty() ? null : new ArrayList<>(hosts), null, null, null, null);
    }

    private static ResultCard googleCard(Map<String, Object> args, Map<String, Object> result) {
        String summary = str(result.get("summary")).trim();
        if (summary.isBlank()) {
            return null;
        }
        String[] sentences = summary.split("(?<=[.!?。])\\s+");
        String lead = clip(sentences[0], 110);
        List<String> points = new ArrayList<>();
        for (int i = 1; i < sentences.length && points.size() < 3; i++) {
            String s = clip(sentences[i], 90);
            if (!s.isBlank()) {
                points.add(s);
            }
        }
        List<String> sources = new ArrayList<>();
        if (result.get("sources") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && sources.size() < 3) {
                    String t = clip(str(m.get("title")), 30);
                    if (!t.isBlank()) {
                        sources.add(t);
                    }
                }
            }
        }
        String query = clip(str(args == null ? null : args.get("query")), MAX_TITLE);
        return new ResultCard(newId(), "search", query.isBlank() ? CardLang.t("search.title", L()) : query, lead,
                points.isEmpty() ? null : points, sources.isEmpty() ? null : sources, null, null, null, null);
    }

    // ───────────────────────── 그래프·표 (show_chart) ─────────────────────────

    private record Parsed(ResultCard card, String error) {
    }

    private static Parsed fail(String message) {
        return new Parsed(null, message);
    }

    private static Parsed parseChart(Map<String, Object> args) {
        if (args == null) {
            return fail("인자가 없습니다.");
        }
        String kind = str(args.get("kind")).trim().toLowerCase();
        String title = clip(str(args.get("title")), MAX_TITLE);
        String unit = clip(str(args.get("unit")), 8);
        String lead = clip(str(args.get("lead")), 80);
        if (title.isBlank()) {
            return fail("title은 필수입니다.");
        }
        String note = CardLang.t("chart.note", L());
        switch (kind) {
            case "pie", "bar", "line" -> {
                List<String> labels = strings(args.get("labels"));
                List<Double> values = numbers(args.get("values"));
                int max = kind.equals("line") ? 30 : kind.equals("pie") ? 8 : 12;
                if (labels == null || values == null || labels.size() != values.size()) {
                    return fail("labels와 values는 같은 개수의 목록이어야 합니다.");
                }
                if (labels.size() < 2 || labels.size() > max) {
                    return fail(kind + " 그래프는 항목이 2~" + max + "개여야 합니다.");
                }
                double sum = 0;
                List<Object> data = new ArrayList<>();
                for (int i = 0; i < labels.size(); i++) {
                    double v = values.get(i);
                    if (Math.abs(v) > MAX_ABS || (kind.equals("pie") && v < 0)) {
                        return fail("값이 허용 범위를 벗어났습니다(원형 그래프는 0 이상).");
                    }
                    sum += v;
                    data.add(List.of(clip(labels.get(i), MAX_LABEL), v));
                }
                if (kind.equals("pie") && sum <= 0) {
                    return fail("원형 그래프의 합이 0입니다.");
                }
                return new Parsed(new ResultCard(newId(), "stats", title, blankToNull(lead), null, null, null,
                        new ResultCard.Chart(kind, blankToNull(unit), data), null, note), null);
            }
            case "candle" -> {
                List<Double> o = numbers(args.get("open")), h = numbers(args.get("high")),
                        l = numbers(args.get("low")), c = numbers(args.get("close")), v = numbers(args.get("volume"));
                if (o == null || h == null || l == null || c == null) {
                    return fail("open, high, low, close 목록이 모두 필요합니다.");
                }
                int n = o.size();
                if (n < 5 || n > 60 || h.size() != n || l.size() != n || c.size() != n || (v != null && v.size() != n)) {
                    return fail("캔들은 5~60개이고 모든 목록의 개수가 같아야 합니다.");
                }
                List<Object> data = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double oo = o.get(i), hh = h.get(i), ll = l.get(i), cc = c.get(i);
                    if (oo <= 0 || cc <= 0 || hh <= 0 || ll <= 0 || hh > MAX_ABS) {
                        return fail("가격은 0보다 커야 합니다.");
                    }
                    if (hh < Math.max(oo, cc) || ll > Math.min(oo, cc)) {
                        return fail((i + 1) + "번째 캔들의 고가/저가가 시가·종가와 맞지 않습니다.");
                    }
                    Map<String, Object> candle = new LinkedHashMap<>();
                    candle.put("o", oo);
                    candle.put("h", hh);
                    candle.put("l", ll);
                    candle.put("c", cc);
                    candle.put("v", v == null ? 0.0 : Math.max(0, v.get(i)));
                    data.add(candle);
                }
                return new Parsed(new ResultCard(newId(), "stock", title, blankToNull(lead), null, null, null,
                        new ResultCard.Chart("candle", blankToNull(unit.isBlank() ? "원" : unit), data), null,
                        CardLang.t("stock.note", L())), null);
            }
            case "table" -> {
                List<String> cols = strings(args.get("columns"));
                List<String> rawRows = strings(args.get("rows"));
                if (cols == null || rawRows == null || cols.size() < 2 || cols.size() > 4) {
                    return fail("columns는 2~4개, rows는 1~8개여야 합니다.");
                }
                if (rawRows.isEmpty() || rawRows.size() > 8) {
                    return fail("rows는 1~8개여야 합니다.");
                }
                List<String> cleanCols = new ArrayList<>();
                for (String col : cols) {
                    cleanCols.add(clip(col, MAX_CELL));
                }
                List<List<String>> rows = new ArrayList<>();
                for (String row : rawRows) {
                    String[] cells = row.split("\\|", -1);
                    List<String> r = new ArrayList<>();
                    for (int i = 0; i < cols.size(); i++) {
                        r.add(i < cells.length ? clip(cells[i], MAX_CELL) : "");
                    }
                    rows.add(r);
                }
                return new Parsed(new ResultCard(newId(), "table", title, blankToNull(lead), null, null, null, null,
                        new ResultCard.Table(cleanCols, rows, null), note), null);
            }
            default -> {
                return fail("kind는 pie, bar, line, candle, table 중 하나여야 합니다.");
            }
        }
    }

    // ───────────────────────── 개념 설명 카드 ─────────────────────────

    private static final java.util.regex.Pattern ASKS_CONCEPT = java.util.regex.Pattern.compile(
            "(에\\s*대해|에\\s*관해|이란|란\\s|뭐야|뭔가|뭐예요|무엇|설명|알려\\s*줘|알려\\s*줄래|알려\\s*주세요|개념|원리|차이|어떻게\\s*(작동|동작|돼|되)|"
                    + "what\\s+(is|are|does)|explain|tell\\s+me\\s+about|how\\s+(does|do)|difference\\s+between|"
                    + "とは|について|説明|教えて|什么是|是什么|解释|讲讲|介绍一下|qué\\s+(es|son)|explica|cuéntame|cómo\\s+funciona)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 일정·알림·기억 같은 '명령' 말은 개념 질문이 아니다. */
    private static final java.util.regex.Pattern IS_COMMAND = java.util.regex.Pattern.compile(
            "(일정|리마인더|알림|기억해|메모|삭제|추가해|등록|취소|유튜브|노래|틀어|날씨|뉴스|주가|환율|오늘|내일|지금|현재|검색|schedule|remind|weather|news)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TAIL = java.util.regex.Pattern.compile(
            "\\s*(에\\s*대해서?|에\\s*관해서?|이란|란|이?\\s*뭐야|이?\\s*뭔가요?|이?\\s*뭐예요|이?\\s*무엇(인가요|이야|이에요)?|"
                    + "좀|한번|다시|자세히|쉽게|간단히|알려\\s*줄래|알려\\s*줘|알려\\s*주세요|설명\\s*해\\s*줘|설명\\s*해\\s*줄래|설명\\s*해\\s*주세요|"
                    + "설명|해\\s*줘|해\\s*줄래|줘|줄래|주세요|요|\\?|\\.|!)\\s*$");
    private static final java.util.regex.Pattern HEAD_EN = java.util.regex.Pattern.compile(
            "^\\s*(please\\s+)?(what\\s+(is|are)|explain|tell\\s+me\\s+about|how\\s+does|how\\s+do)\\s+(a\\s+|an\\s+|the\\s+)?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 함수 호출로 만들어진 카드가 하나도 없는데, 사용자가 개념·원리를 물었고 답이 충분히 길면
     * 그 답변을 제목/요약/핵심 항목으로 나눠 '개념 설명' 카드로 만든다(추가 모델 호출 없음).
     * 조건이 안 맞으면 받은 카드를 그대로 돌려준다.
     */
    public static List<ResultCard> withExplanation(List<ResultCard> cards, String userMessage, String reply) {
        if (cards != null && !cards.isEmpty()) {
            return cards;
        }
        ResultCard card = explainCard(userMessage, reply);
        return card == null ? (cards == null ? List.of() : cards) : List.of(card);
    }

    static ResultCard explainCard(String userMessage, String reply) {
        return explainCard(userMessage, reply, false);
    }

    /** 수식 줄이 있는 답변처럼, 질문 문구가 개념 질문 패턴에 안 맞아도 개념 카드를 만들어야 할 때. */
    static ResultCard explainCardForced(String userMessage, String reply) {
        return explainCard(userMessage, reply, true);
    }

    private static ResultCard explainCard(String userMessage, String reply, boolean force) {
        if (userMessage == null || reply == null) {
            return null;
        }
        String msg = userMessage.trim();
        // 답변 끝의 [[수식: …]] 줄은 카드의 '수식' 칸으로 가므로 설명 문장에서는 뺀다.
        String text = ConceptEnricher.extract(reply).reply().replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
        if (msg.isEmpty() || msg.length() > 120 || text.length() < 100 || msg.startsWith("(")
                || (!force && !ASKS_CONCEPT.matcher(msg).find()) || IS_COMMAND.matcher(msg).find()) {
            return null;
        }
        // 문장 단위로 나눈다(마침표/물음표/느낌표 뒤 공백, 또는 줄바꿈). 목록 기호는 떼어 낸다.
        List<String> sentences = new ArrayList<>();
        for (String part : text.split("(?<=[.!?。])\\s+|\\n+")) {
            String t = part.replaceAll("^[\\s\\-•*·\\d.)]+", "").replace("**", "").trim();
            if (t.length() >= 8) {
                sentences.add(t);
            }
        }
        if (sentences.size() < 2) {
            return null;
        }
        String title = msg;
        title = HEAD_EN.matcher(title).replaceFirst("");
        for (int i = 0; i < 6; i++) { // 말끝 군더더기를 여러 겹 벗긴다("…에 대해 알려줄래?")
            String next = TAIL.matcher(title).replaceFirst("");
            if (next.equals(title)) {
                break;
            }
            title = next;
        }
        title = title.replaceAll("[?.!]+$", "").trim();
        if (title.isBlank()) {
            title = msg;
        }
        List<String> points = new ArrayList<>();
        for (int i = 1; i < sentences.size() && points.size() < 6; i++) {
            points.add(clip(sentences.get(i), 140));
        }
        return new ResultCard(newId(), "explain", clip(title, MAX_TITLE), clip(sentences.get(0), 160), points, null,
                null, null, null, CardLang.t("explain.note", L()));
    }

    // ───────────────────────── 공통 도구 ─────────────────────────

    /** 이번 요청에서 사용자가 쓴 언어(ResultCardContext가 들고 있다). */
    private static String L() {
        return ResultCardContext.lang();
    }

    private static String newId() {
        return "c-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 제어문자·줄바꿈을 공백으로 바꾸고 길이를 자른다. */
    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, Math.max(0, max - 1)).trim() + "…" : cleaned;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static LocalDateTime parseTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String hostOf(String link) {
        if (link == null || !(link.startsWith("https://") || link.startsWith("http://"))) {
            return "";
        }
        try {
            String host = URI.create(link).getHost();
            return host == null ? "" : clip(host.startsWith("www.") ? host.substring(4) : host, 30);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(str(o));
        }
        return out;
    }

    private static List<Double> numbers(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Double> out = new ArrayList<>();
        for (Object o : list) {
            double d;
            if (o instanceof Number n) {
                d = n.doubleValue();
            } else {
                try {
                    d = Double.parseDouble(str(o).replace(",", "").trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            out.add(d);
        }
        return out;
    }
}
