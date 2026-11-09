package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.chat.ResultCard;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.Pattern;

/**
 * 꼬리 탐색 결과(모델 JSON·공개 API 응답)를 결과 카드로 바꾸는 순수 함수 모음. 네트워크·모델 호출이 없어 단독으로 시험할 수 있다.
 *
 * 모델이 준 글은 믿지 않는다: 태그를 지우고, 길이·개수를 자르고, 수식은 ConceptEnricher.validTex로 거르고,
 * 그래프는 식만 받아 SafeExpr로 서버가 계산한다. 숫자는 공개 API 값만 쓴다.
 * 고정 문구는 한국어·영어를 지원하고, 그 밖의 언어는 영어로 나간다(본문은 모델이 사용자 언어로 쓴다).
 */
public final class DeepDiveCards {

    private DeepDiveCards() {
    }

    private static final Pattern TAGS = Pattern.compile("<[^>]{0,200}>");
    private static final Pattern CTRL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

    static String tr(String lang, String ko, String en) {
        return "ko".equals(lang) ? ko : en;
    }

    static String clean(Object o, int max) {
        if (o == null) {
            return "";
        }
        String s = CTRL.matcher(TAGS.matcher(String.valueOf(o)).replaceAll("")).replaceAll("").replace('\n', ' ').strip();
        s = s.replaceAll("\\s{2,}", " ");
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).strip() + "…";
    }

    static List<String> strings(Object o, int maxItems, int maxLen) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                String s = clean(x, maxLen);
                if (!s.isBlank() && out.size() < maxItems) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static List<ResultCard.Formula> formulas(Object o) {
        List<ResultCard.Formula> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (out.size() >= 3 || !(x instanceof Map<?, ?> m)) {
                    continue;
                }
                String tex = m.get("tex") == null ? "" : String.valueOf(m.get("tex")).strip();
                if (tex.length() > 240 || !ConceptEnricher.validTex(tex)) {
                    continue;
                }
                String cap = clean(m.get("caption"), 90);
                out.add(new ResultCard.Formula(tex, cap.isBlank() ? null : cap));
            }
        }
        return out;
    }

    private static String id() {
        return "dd-" + Long.toString(System.nanoTime(), 36);
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? dflt : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Double numOrNull(Object o) {
        if (o instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        return null;
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ───────────────────────── 개념 학습 ─────────────────────────

    /** 1단계: 이 주제가 어떤 갈래(단원)로 이뤄졌는지. */
    public static ResultCard outline(Map<String, Object> j, String lang) {
        String topic = clean(j.get("topic"), 30);
        List<List<String>> rows = new ArrayList<>();
        if (j.get("units") instanceof List<?> units) {
            for (Object u : units) {
                if (rows.size() < 8 && u instanceof Map<?, ?> m) {
                    String name = clean(m.get("name"), 24), sum = clean(m.get("summary"), 70);
                    if (!name.isBlank() && !sum.isBlank()) {
                        rows.add(List.of(name, sum));
                    }
                }
            }
        }
        if (topic.isBlank() || rows.size() < 2) {
            return null;
        }
        return new ResultCard(id(), "table", tr(lang, topic + " 단원 지도", topic + " — map of topics"),
                clean(j.get("lead"), 100).isBlank() ? null : clean(j.get("lead"), 100), null, null, null, null,
                new ResultCard.Table(List.of(tr(lang, "단원", "Unit"), tr(lang, "핵심 내용", "Key idea")), rows, null),
                tr(lang, "더 알아보는 중이에요. 다음 카드에서 풀이법을 보여드릴게요.", "Digging deeper — a worked example comes next."))
                .withLang(lang);
    }

    /** 2단계: 어떻게 푸는지(예제와 풀이 순서). */
    public static ResultCard solve(Map<String, Object> j, String lang, String topic) {
        List<String> steps = strings(j.get("steps"), 7, 150);
        if (steps.size() < 2) {
            return null;
        }
        List<String> points = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            points.add((i + 1) + ". " + steps.get(i));
        }
        String answer = clean(j.get("answer"), 100);
        if (!answer.isBlank()) {
            points.add(tr(lang, "답: ", "Answer: ") + answer);
        }
        String problem = clean(j.get("problem"), 160);
        return new ResultCard(id(), "explain", tr(lang, "풀이법 · " + topic, "How to solve · " + topic),
                problem.isBlank() ? null : tr(lang, "예제: ", "Example: ") + problem, points, null, null, null, null,
                tr(lang, "풀이 순서는 AI가 정리한 내용이에요. 시험 전에는 교재로 한 번 더 확인하세요.",
                        "Steps were composed by AI — double-check with your textbook."),
                lang, formulas(j.get("formulas")), null, null, null);
    }

    /** 3단계: 왜 이런 공식·원리가 나왔는지. 식을 주면 서버가 계산해 그래프로 보여 준다. */
    public static ResultCard why(Map<String, Object> j, String lang, String topic) {
        List<String> why = strings(j.get("why"), 5, 170);
        if (why.isEmpty()) {
            return null;
        }
        ResultCard.Chart chart = null;
        String plotTitle = null;
        if (j.get("plot") instanceof Map<?, ?> p) {
            try {
                String expr = String.valueOf(p.get("expr"));
                double xmin = num(p.get("xmin"), -5), xmax = num(p.get("xmax"), 5);
                if (!(xmin < xmax) || xmax - xmin > 1000 || Math.abs(xmin) > 1e4 || Math.abs(xmax) > 1e4) {
                    throw new IllegalArgumentException("범위");
                }
                DoubleUnaryOperator f = SafeExpr.compile(expr);
                List<Object> series = new ArrayList<>();
                List<double[]> pts = SafeExpr.sample(f, xmin, xmax, 48);
                if (pts.size() >= 8) {
                    series.add(series("f(x) = " + clean(expr.replaceAll("^(f|y)\\s*(\\(\\s*x\\s*\\))?\\s*=\\s*", ""), 40), pts));
                    if (Boolean.TRUE.equals(p.get("derivative")) || "true".equals(String.valueOf(p.get("derivative")))) {
                        double h = (xmax - xmin) / 2000.0;
                        List<double[]> dp = SafeExpr.sample(SafeExpr.derivative(f, h), xmin, xmax, 48);
                        if (dp.size() >= 8) {
                            series.add(series("f′(x)", dp));
                        }
                    }
                    chart = new ResultCard.Chart("plot", null, series);
                    plotTitle = clean(p.get("title"), 60);
                }
            } catch (RuntimeException e) {
                chart = null; // 식이 이상하면 그래프만 빼고 설명은 낸다
            }
        }
        List<String> points = new ArrayList<>(why);
        return new ResultCard(id(), "explain", tr(lang, "왜 이렇게 될까? · " + topic, "Why does it work? · " + topic),
                plotTitle == null || plotTitle.isBlank() ? null : plotTitle, points, null, null, chart, null,
                chart != null ? tr(lang, "그래프는 식을 서버가 직접 계산해 그렸어요(도함수는 수치 계산 값).",
                        "The graph is computed from the formula on the server (derivative is numerical).") : null,
                lang, formulas(j.get("formulas")), null, null, null);
    }

    private static Map<String, Object> series(String name, List<double[]> pts) {
        List<List<Double>> list = new ArrayList<>();
        for (double[] p : pts) {
            list.add(List.of(p[0], p[1]));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pts", list);
        return m;
    }

    /** 4단계: 이어서 물어볼 만한 질문. */
    public static ResultCard questions(Map<String, Object> j, String lang, String topic) {
        List<String> qs = strings(j.get("questions"), 5, 90);
        if (qs.size() < 2) {
            return null;
        }
        return new ResultCard(id(), "explain", tr(lang, "더 파고들 질문 · " + topic, "Keep digging · " + topic),
                tr(lang, "이런 점이 궁금해질 거예요. 말로 물어보면 이어서 설명할게요.", "You may wonder about these — just ask and I'll continue."),
                qs, null, null, null, null, null, lang, null, null, null);
    }

    // ───────────────────────── 날씨 ─────────────────────────

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String mmdd(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(5, 7) + "/" + iso.substring(8, 10) : String.valueOf(iso);
    }

    /** 좌표를 알면 지구본(earth.nullschool.net) 바로가기를 붙인다. */
    public static ResultCard forecast(Map<String, Object> data, String place, String lang, double lat, double lon) {
        ResultCard c = forecast(data, place, lang);
        return c == null ? null : c.withLinks(earthLinks(lat, lon, lang));
    }

    /** 주소의 # 뒤에 시각·레이어·투영·표시 지점이 들어간다(사이트가 설정을 주소에 저장하는 방식). 지점은 loc=경도,위도. */
    static List<ResultCard.Link> earthLinks(double lat, double lon, String lang) {
        if (!DeepDiveSources.validCoord(lat, lon)) {
            return List.of();
        }
        String pos = String.format(Locale.ROOT, "%.2f,%.2f", lon, lat);
        String base = "https://earth.nullschool.net/#current/wind/surface/level/";
        return List.of(
                new ResultCard.Link(tr(lang, "지구 바람 지도 보기", "Earth wind map"), base + "orthographic=" + pos + ",1400/loc=" + pos),
                new ResultCard.Link(tr(lang, "지구 기온 지도 보기", "Earth temperature map"), base + "overlay=temp/orthographic=" + pos + ",1400/loc=" + pos));
    }

    /** 모델에게 줄 숫자 요약. 해석은 이 숫자 안에서만 하도록 시킨다. */
    static String weatherDigest(Map<String, Object> forecast, Map<Integer, Double> pastMeans, Map<String, Object> air) {
        StringBuilder sb = new StringBuilder();
        if (forecast.get("current") instanceof Map<?, ?> c && numOrNull(c.get("temperature_2m")) != null) {
            sb.append("now: temp ").append(f1(num(c.get("temperature_2m"), 0))).append("C");
            if (numOrNull(c.get("relative_humidity_2m")) != null) {
                sb.append(", humidity ").append(Math.round(num(c.get("relative_humidity_2m"), 0))).append("%");
            }
            sb.append('\n');
        }
        if (forecast.get("daily") instanceof Map<?, ?> d) {
            List<?> days = list(d.get("time")), hi = list(d.get("temperature_2m_max")), lo = list(d.get("temperature_2m_min")), pop = list(d.get("precipitation_probability_max"));
            for (int i = 0; i < Math.min(Math.min(days.size(), hi.size()), 7); i++) {
                if (numOrNull(hi.get(i)) == null) {
                    continue;
                }
                sb.append(mmdd(String.valueOf(days.get(i)))).append(": high ").append(f1(num(hi.get(i), 0))).append("C");
                if (i < lo.size() && numOrNull(lo.get(i)) != null) {
                    sb.append(", low ").append(f1(num(lo.get(i), 0))).append("C");
                }
                if (i < pop.size() && numOrNull(pop.get(i)) != null) {
                    sb.append(", rain chance ").append(Math.round(num(pop.get(i), 0))).append("%");
                }
                sb.append('\n');
            }
            double mean = DeepDiveSources.forecastMean(forecast);
            if (!Double.isNaN(mean) && pastMeans != null && !pastMeans.isEmpty()) {
                double sum = 0;
                for (double v : pastMeans.values()) {
                    sum += v;
                }
                sb.append("next-7-day mean ").append(f1(mean)).append("C vs same days in past ").append(pastMeans.size())
                        .append(" years mean ").append(f1(sum / pastMeans.size())).append("C\n");
            }
        }
        if (air != null && air.get("current") instanceof Map<?, ?> c) {
            if (numOrNull(c.get("pm2_5")) != null) {
                sb.append("PM2.5 ").append(Math.round(num(c.get("pm2_5"), 0))).append(" ug/m3\n");
            }
            if (numOrNull(c.get("pm10")) != null) {
                sb.append("PM10 ").append(Math.round(num(c.get("pm10"), 0))).append(" ug/m3\n");
            }
        }
        return sb.toString();
    }

    /** 4단계: 숫자를 모델이 읽어 준 해석. 숫자 자체는 앞 카드에 있으므로 여기서는 글만 싣는다. */
    public static ResultCard interpret(Map<String, Object> j, String place, String lang) {
        String summary = clean(j.get("summary"), 260);
        List<String> tips = strings(j.get("tips"), 4, 90);
        if (summary.isBlank()) {
            return null;
        }
        return new ResultCard(id(), "explain", tr(lang, place + " 날씨 한눈에 해석", place + " — what it means"), summary, tips,
                List.of("Open-Meteo"), null, null, null,
                tr(lang, "위 카드의 수치를 AI가 읽고 풀어 쓴 글이에요. 틀릴 수 있으니 중요한 일정은 기상청 예보도 확인하세요.",
                        "An AI reading of the numbers above — it can be wrong; check your national weather service for anything important."))
                .withLang(lang);
    }

    /** 화면 공유 분석 결과 카드. j는 모델이 만든 JSON — 믿지 않고 거른다. */
    public static ResultCard screen(Map<String, Object> j, String lang) {
        String lead = clean(j.get("lead"), 220);
        List<String> points = strings(j.get("points"), 6, 140);
        if (lead.isBlank() && points.isEmpty()) {
            return null;
        }
        String title = clean(j.get("title"), 40);
        boolean unclear = Boolean.FALSE.equals(j.get("readable"));
        return new ResultCard(id(), "explain", title.isBlank() ? tr(lang, "화면 분석", "Screen analysis") : title,
                lead.isBlank() ? null : lead, points, null, null, null, null,
                unclear ? tr(lang, "화면이 흐리거나 작아서 일부만 읽었어요. 확대해서 다시 공유하면 더 정확해요.",
                        "Parts of the screen were hard to read. Zoom in and share again for a better result.")
                        : tr(lang, "공유한 화면 한 장을 AI가 읽은 결과예요. 틀릴 수 있어요.", "An AI reading of the one screenshot you shared — it can be wrong."))
                .withLang(lang);
    }

    /** 1단계: 현재 상태와 7일 예보. data는 Open-Meteo forecast 응답. */
    public static ResultCard forecast(Map<String, Object> data, String place, String lang) {
        if (!(data.get("daily") instanceof Map<?, ?> d)) {
            return null;
        }
        List<?> days = list(d.get("time")), tmax = list(d.get("temperature_2m_max")), tmin = list(d.get("temperature_2m_min")),
                pop = list(d.get("precipitation_probability_max"));
        int n = Math.min(Math.min(days.size(), tmax.size()), 7);
        if (n < 3) {
            return null;
        }
        List<Object> chartData = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Double v = numOrNull(tmax.get(i));
            if (v != null) {
                chartData.add(List.of(mmdd(String.valueOf(days.get(i))), v));
            }
        }
        if (chartData.size() < 3) {
            return null;
        }
        String lead = null;
        if (data.get("current") instanceof Map<?, ?> c && numOrNull(c.get("temperature_2m")) != null) {
            String feels = numOrNull(c.get("apparent_temperature")) == null ? "" : tr(lang, "(체감 " + f1(num(c.get("apparent_temperature"), 0)) + "°C)", " (feels " + f1(num(c.get("apparent_temperature"), 0)) + "°C)");
            String hum = numOrNull(c.get("relative_humidity_2m")) == null ? "" : tr(lang, ", 습도 " + Math.round(num(c.get("relative_humidity_2m"), 0)) + "%", ", humidity " + Math.round(num(c.get("relative_humidity_2m"), 0)) + "%");
            lead = tr(lang, "지금 ", "Now ") + f1(num(c.get("temperature_2m"), 0)) + "°C" + feels + hum;
        }
        List<String> points = new ArrayList<>();
        if (days.size() >= 2 && tmax.size() >= 2 && tmin.size() >= 2 && numOrNull(tmax.get(1)) != null && numOrNull(tmin.get(1)) != null) {
            double lo = num(tmin.get(1), 0), hi = num(tmax.get(1), 0);
            String rain = pop.size() > 1 && numOrNull(pop.get(1)) != null ? tr(lang, ", 강수확률 " + Math.round(num(pop.get(1), 0)) + "%", ", rain chance " + Math.round(num(pop.get(1), 0)) + "%") : "";
            points.add(tr(lang, "내일 ", "Tomorrow ") + f1(lo) + "~" + f1(hi) + "°C" + rain);
            if ((pop.size() > 1 && num(pop.get(1), 0) >= 50) || (!pop.isEmpty() && num(pop.get(0), 0) >= 50)) {
                points.add(tr(lang, "오늘이나 내일 비·눈 가능성이 높아요. 우산을 챙기세요.", "Rain or snow is likely today or tomorrow — bring an umbrella."));
            }
            if (lo <= 3) {
                points.add(tr(lang, "내일 아침은 많이 추워요. 두꺼운 겉옷이 좋아요.", "Tomorrow morning is cold — dress warmly."));
            } else if (hi >= 30) {
                points.add(tr(lang, "내일은 더워요. 물을 자주 마시고 한낮 야외 활동은 피하세요.", "Tomorrow is hot — drink water and avoid midday outdoors."));
            } else if (hi - lo >= 10) {
                points.add(tr(lang, "일교차가 커요. 겹쳐 입을 수 있는 옷이 좋아요.", "Big day-night swing — layer your clothes."));
            }
        }
        List<String> wet = new ArrayList<>();
        for (int i = 2; i < Math.min(n, pop.size()); i++) {
            if (numOrNull(pop.get(i)) != null && num(pop.get(i), 0) >= 60) {
                wet.add(mmdd(String.valueOf(days.get(i))));
            }
        }
        if (!wet.isEmpty()) {
            points.add(tr(lang, "이후 비 소식(강수확률 60% 이상): ", "Wet days ahead (60%+): ") + String.join(", ", wet));
        }
        return new ResultCard(id(), "stats", tr(lang, place + " 일주일 예보", place + " — 7-day forecast"), lead, points,
                List.of("Open-Meteo"), null, new ResultCard.Chart("line", "°C", chartData), null,
                tr(lang, "일별 최고기온(°C). 예보 모델 값이라 실제와 다를 수 있어요.", "Daily high (°C). Model forecast — may differ from reality."))
                .withLang(lang);
    }

    /** 2단계: 앞으로 7일 평균기온을 최근 몇 해의 같은 기간과 견준다. */
    public static ResultCard stats(double forecastMean, Map<Integer, Double> pastMeans, String place, String lang) {
        if (pastMeans.size() < 2 || !Double.isFinite(forecastMean)) {
            return null;
        }
        double avg = pastMeans.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double diff = forecastMean - avg;
        List<Object> data = new ArrayList<>();
        pastMeans.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> data.add(List.of(String.valueOf(e.getKey()), Math.round(e.getValue() * 10) / 10.0)));
        data.add(List.of(tr(lang, "올해 예보", "Forecast"), Math.round(forecastMean * 10) / 10.0));
        String dir = Math.abs(diff) < 0.5 ? tr(lang, "비슷해요", "about the same")
                : diff > 0 ? tr(lang, f1(Math.abs(diff)) + "°C 높아요", f1(Math.abs(diff)) + "°C warmer")
                : tr(lang, f1(Math.abs(diff)) + "°C 낮아요", f1(Math.abs(diff)) + "°C cooler");
        List<String> points = List.of(
                tr(lang, "앞으로 7일 평균 " + f1(forecastMean) + "°C", "Next 7 days average " + f1(forecastMean) + "°C"),
                tr(lang, "최근 " + pastMeans.size() + "년 같은 기간 평균 " + f1(avg) + "°C", "Same period, last " + pastMeans.size() + " years: " + f1(avg) + "°C"));
        return new ResultCard(id(), "stats", tr(lang, place + " 평년과 비교", place + " — vs. recent years"),
                tr(lang, "올해는 최근 같은 시기보다 " + dir + ".", "This week is " + dir + " than the same week in recent years."),
                points, List.of("Open-Meteo Archive"), null, new ResultCard.Chart("bar", "°C", data), null,
                tr(lang, "통계는 같은 기간 일평균기온의 단순 평균이에요.", "Simple average of daily mean temperatures for the same dates."))
                .withLang(lang);
    }

    static String pm25Grade(double v, String lang) {
        return v <= 15 ? tr(lang, "좋음", "Good") : v <= 35 ? tr(lang, "보통", "Moderate") : v <= 75 ? tr(lang, "나쁨", "Unhealthy") : tr(lang, "매우 나쁨", "Very unhealthy");
    }

    static String pm10Grade(double v, String lang) {
        return v <= 30 ? tr(lang, "좋음", "Good") : v <= 80 ? tr(lang, "보통", "Moderate") : v <= 150 ? tr(lang, "나쁨", "Unhealthy") : tr(lang, "매우 나쁨", "Very unhealthy");
    }

    /** 3단계: 미세먼지. data는 Open-Meteo air-quality 응답. */
    public static ResultCard air(Map<String, Object> data, String place, String lang) {
        Double pm25 = null, pm10 = null;
        if (data.get("current") instanceof Map<?, ?> c) {
            pm25 = numOrNull(c.get("pm2_5"));
            pm10 = numOrNull(c.get("pm10"));
        }
        if (pm25 == null && pm10 == null) {
            return null;
        }
        List<String> points = new ArrayList<>();
        if (pm25 != null) {
            points.add(tr(lang, "초미세먼지(PM2.5) ", "PM2.5 ") + Math.round(pm25) + "㎍/㎥ · " + pm25Grade(pm25, lang));
        }
        if (pm10 != null) {
            points.add(tr(lang, "미세먼지(PM10) ", "PM10 ") + Math.round(pm10) + "㎍/㎥ · " + pm10Grade(pm10, lang));
        }
        boolean bad = (pm25 != null && pm25 > 35) || (pm10 != null && pm10 > 80);
        points.add(bad ? tr(lang, "야외 활동은 줄이고 외출할 때는 마스크(KF80 이상)를 쓰세요.", "Limit outdoor activity and wear a mask (KF80+).")
                : tr(lang, "야외 활동에 큰 무리가 없는 수준이에요.", "Fine for normal outdoor activity."));
        List<Object> chart = new ArrayList<>();
        if (data.get("hourly") instanceof Map<?, ?> h) {
            List<?> times = list(h.get("time")), vals = list(h.get("pm2_5"));
            int start = Math.min(nowIndex(times, data), Math.max(0, times.size() - 1));
            for (int i = start; i < Math.min(times.size(), vals.size()) && chart.size() < 24; i += 1) {
                Double v = numOrNull(vals.get(i));
                String t = String.valueOf(times.get(i));
                if (v != null && t.length() >= 13) {
                    chart.add(List.of(t.substring(11, 13) + tr(lang, "시", "h"), v));
                }
            }
        }
        String grade = pm25 != null ? pm25Grade(pm25, lang) : pm10Grade(pm10, lang);
        return new ResultCard(id(), "stats", tr(lang, place + " 대기 상태", place + " — air quality"),
                tr(lang, "현재 대기질은 '" + grade + "' 수준이에요.", "Air quality is \"" + grade + "\" right now."), points,
                List.of("Open-Meteo Air Quality"), null, chart.size() >= 4 ? new ResultCard.Chart("line", "㎍/㎥", chart) : null, null,
                tr(lang, "등급 기준은 국내 환경부 PM 구간을 따랐어요. 모델 추정값이라 측정소 값과 다를 수 있어요.",
                        "Grades follow Korean PM thresholds. Modelled values — may differ from stations."))
                .withLang(lang);
    }

    private static int nowIndex(List<?> times, Map<String, Object> data) {
        String cur = data.get("current") instanceof Map<?, ?> c && c.get("time") != null ? String.valueOf(c.get("time")) : "";
        if (cur.length() >= 13) {
            String key = cur.substring(0, 13);
            for (int i = 0; i < times.size(); i++) {
                if (String.valueOf(times.get(i)).startsWith(key)) {
                    return i;
                }
            }
        }
        return 0;
    }

    // ───────────────────────── 등교·교통 ─────────────────────────

    public record Route(double distanceM, double durationS, List<String> roads) {
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static List<ResultCard.Link> mapLinks(String oName, double oLat, double oLon, String dName, double dLat, double dLon, String lang) {
        String o = clean(oName, 40), d = clean(dName, 40);
        String ll = String.format(Locale.ROOT, "%.6f,%.6f", oLat, oLon);
        String dl = String.format(Locale.ROOT, "%.6f,%.6f", dLat, dLon);
        return List.of(
                new ResultCard.Link(tr(lang, "카카오맵 대중교통 길찾기", "Kakao Map — transit"),
                        "https://map.kakao.com/link/by/publictransit/" + enc(o) + "," + ll + "/" + enc(d) + "," + dl),
                new ResultCard.Link(tr(lang, "네이버지도 길찾기", "Naver Map — directions"),
                        "https://map.naver.com/p/directions/" + String.format(Locale.ROOT, "%.6f,%.6f", oLon, oLat) + "," + enc(o)
                                + "/" + String.format(Locale.ROOT, "%.6f,%.6f", dLon, dLat) + "," + enc(d) + "/-/transit"),
                new ResultCard.Link(tr(lang, "구글지도 길찾기", "Google Maps — directions"),
                        "https://www.google.com/maps/dir/?api=1&origin=" + ll + "&destination=" + dl + "&travelmode=transit"));
    }

    /** 1단계: 출발지→목적지 경로 후보(승용차 기준, OSRM). 교통 상황은 반영되지 않는다. */
    public static ResultCard routes(String origin, String dest, List<Route> routes, List<ResultCard.Link> links, String lang) {
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        int best = 0;
        for (int i = 1; i < routes.size(); i++) {
            if (routes.get(i).durationS() < routes.get(best).durationS()) {
                best = i;
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(routes.size(), 3); i++) {
            Route r = routes.get(i);
            String name = tr(lang, "경로 " + (i + 1) + (i == best ? " (최단 시간)" : ""), "Route " + (i + 1) + (i == best ? " (fastest)" : ""));
            String roads = r.roads().isEmpty() ? "-" : String.join(" → ", r.roads().subList(0, Math.min(3, r.roads().size())));
            rows.add(List.of(name, f1(r.distanceM() / 1000.0) + "km", Math.round(r.durationS() / 60.0) + tr(lang, "분", " min"), clean(roads, 40)));
        }
        Route b = routes.get(best);
        return new ResultCard(id(), "table", tr(lang, clean(origin, 20) + " → " + clean(dest, 20) + " 경로", clean(origin, 20) + " → " + clean(dest, 20)),
                tr(lang, "승용차 기준 가장 빠른 경로는 약 " + Math.round(b.durationS() / 60.0) + "분(" + f1(b.distanceM() / 1000.0) + "km)이에요.",
                        "Fastest by car: about " + Math.round(b.durationS() / 60.0) + " min (" + f1(b.distanceM() / 1000.0) + " km)."),
                null, List.of("OpenStreetMap · OSRM"), null, null,
                new ResultCard.Table(List.of(tr(lang, "경로", "Route"), tr(lang, "거리", "Dist."), tr(lang, "시간*", "Time*"), tr(lang, "주요 도로", "Main roads")), rows, best),
                tr(lang, "* 교통 상황이 반영되지 않은 예상이에요. 대중교통·실시간 혼잡은 아래 지도 링크에서 확인하세요.",
                        "* Free-flow estimate — no live traffic. Use the map links for transit and real-time congestion."))
                .withLang(lang).withLinks(links);
    }

    /**
     * 2단계: 최근 기사에서 본 교통 통제·시위·파업·사고. j는 모델이 기사 제목·요약만 보고 정리한 JSON
     * {"level":"low|medium|high","points":[...]}, titles는 근거 기사 제목.
     */
    public static ResultCard traffic(Map<String, Object> j, List<String> titles, String dest, String lang) {
        List<String> points = j == null ? List.of() : strings(j.get("points"), 5, 150);
        String level = j == null ? "" : clean(j.get("level"), 10).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(points);
        String lead;
        if (points.isEmpty()) {
            lead = tr(lang, "최근 기사에서 교통 통제·시위·파업 소식은 확인되지 않았어요.", "No recent reports of road closures, protests or strikes were found.");
        } else {
            lead = switch (level) {
                case "high" -> tr(lang, "지연 가능성이 커요. 일찍 출발하거나 우회 경로를 보세요.", "Delays are likely — leave early or consider alternatives.");
                case "medium" -> tr(lang, "일부 구간에 영향이 있을 수 있어요.", "Some stretches may be affected.");
                default -> tr(lang, "눈에 띄는 큰 영향은 없지만 참고할 소식이 있어요.", "Nothing major, but there are items worth noting.");
            };
        }
        out.add(tr(lang, "인구 밀집도·실시간 혼잡도는 별도 API 키가 있어야 조회할 수 있어 이번에는 제외했어요.",
                "Crowd density and live congestion need a separate API key, so they are not included."));
        List<String> src = new ArrayList<>();
        if (titles != null) {
            for (String t : titles) {
                String c = clean(t, 60);
                if (!c.isBlank() && src.size() < 4) {
                    src.add(c);
                }
            }
        }
        return new ResultCard(id(), "explain", tr(lang, clean(dest, 20) + " 교통 변수", clean(dest, 20) + " — traffic factors"), lead, out,
                src.isEmpty() || points.isEmpty() ? null : src, null, null, null,
                tr(lang, "최근 뉴스 검색 결과를 AI가 요약했어요. 정확한 통제 구간은 기관 공지를 확인하세요.",
                        "Summarised by AI from recent news. Check official notices for exact closures."), lang, null, null, null);
    }

    /** 최근 며칠 안의 기사만 남기기 위한 날짜 비교(RFC 1123 pubDate). 읽지 못하면 true(버리지 않는다). */
    static boolean recent(String pubDate, LocalDate today, int days) {
        if (pubDate == null || pubDate.isBlank()) {
            return true;
        }
        try {
            LocalDate d = java.time.ZonedDateTime.parse(pubDate, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
            return !d.isBefore(today.minusDays(days));
        } catch (RuntimeException e) {
            return true;
        }
    }
}
