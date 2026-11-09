package com.jarvisst.Lumi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 꼬리 탐색이 쓰는 공개 데이터 창구 — 모두 API 키가 필요 없다.
 *  - Open-Meteo: 지명 → 좌표, 예보, 지난 기록(평년 비교), 대기질
 *  - Nominatim(OpenStreetMap): 장소 이름 → 좌표
 *  - OSRM 공개 서버: 승용차 경로 후보
 *
 * 안전: 호스트는 코드에 고정돼 있고(사용자 값은 인코딩된 질의값·검증된 숫자로만 들어간다), 응답 크기·제한 시간을 둔다.
 * 어느 호출이든 실패하면 빈 값을 돌려줘서 그 단계만 건너뛴다.
 */
@Service
public class DeepDiveSources {

    private static final Logger log = LoggerFactory.getLogger(DeepDiveSources.class);
    private static final int MAX_BYTES = 2_000_000;
    private static final String UA = "Lumi-student-project/1.0 (university course project)";

    // 시험에서 가짜 서버로 바꿀 수 있게 둔 주소들
    String geocodeBase = "https://geocoding-api.open-meteo.com";
    String forecastBase = "https://api.open-meteo.com";
    String archiveBase = "https://archive-api.open-meteo.com";
    String airBase = "https://air-quality-api.open-meteo.com";
    String nominatimBase = "https://nominatim.openstreetmap.org";
    String osrmBase = "https://router.project-osrm.org";

    public record Place(String name, double lat, double lon) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();
    private long lastNominatim = 0;

    static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static boolean validCoord(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }

    private static String c(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /** GET → JSON(Object: Map 또는 List). 2분 동안 같은 주소는 다시 받지 않는다. */
    private Object getJson(String url, Duration timeout) throws Exception {
        Object[] hit = cache.get(url);
        if (hit != null && (long) hit[0] > System.currentTimeMillis()) {
            return hit[1];
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("User-Agent", UA).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        String body = res.body();
        if (body == null || body.length() > MAX_BYTES) {
            throw new IllegalStateException("응답 크기");
        }
        Object parsed = mapper.readValue(body, new TypeReference<Object>() {
        });
        if (cache.size() > 200) {
            cache.clear();
        }
        cache.put(url, new Object[]{System.currentTimeMillis() + 120_000L, parsed});
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String url) {
        try {
            Object o = getJson(url, Duration.ofSeconds(7));
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            log.warn("[꼬리탐색] 조회 실패 {} — {}", url.replaceAll("\\?.*", ""), e.toString());
            return Map.of();
        }
    }

    // ───────── 날씨 ─────────

    public Optional<Place> geocodeCity(String name, String lang) {
        if (name == null || name.isBlank() || name.length() > 60) {
            return Optional.empty();
        }
        Map<String, Object> r = getMap(geocodeBase + "/v1/search?name=" + q(name.strip()) + "&count=1&format=json&language="
                + ("ko".equals(lang) || "ja".equals(lang) || "zh".equals(lang) || "es".equals(lang) ? lang : "en"));
        if (r.get("results") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m
                && m.get("latitude") instanceof Number la && m.get("longitude") instanceof Number lo
                && validCoord(la.doubleValue(), lo.doubleValue())) {
            String nm = m.get("name") == null ? name : String.valueOf(m.get("name"));
            return Optional.of(new Place(DeepDiveCards.clean(nm, 30), la.doubleValue(), lo.doubleValue()));
        }
        return Optional.empty();
    }

    public Map<String, Object> forecast(double lat, double lon) {
        if (!validCoord(lat, lon)) {
            return Map.of();
        }
        return getMap(forecastBase + "/v1/forecast?latitude=" + c(lat) + "&longitude=" + c(lon)
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=7");
    }

    /** 오늘부터 7일 평균기온(최고·최저의 중간값을 날마다 평균). */
    public static double forecastMean(Map<String, Object> forecast) {
        if (forecast.get("daily") instanceof Map<?, ?> d && d.get("temperature_2m_max") instanceof List<?> hi
                && d.get("temperature_2m_min") instanceof List<?> lo) {
            double sum = 0;
            int n = 0;
            for (int i = 0; i < Math.min(7, Math.min(hi.size(), lo.size())); i++) {
                if (hi.get(i) instanceof Number a && lo.get(i) instanceof Number b) {
                    sum += (a.doubleValue() + b.doubleValue()) / 2.0;
                    n++;
                }
            }
            return n >= 3 ? sum / n : Double.NaN;
        }
        return Double.NaN;
    }

    /** 지난 years년 같은 7일 구간의 일평균기온 평균(연도 → 평균). 못 구한 해는 뺀다. */
    public Map<Integer, Double> pastMeans(double lat, double lon, LocalDate today, int years) {
        if (!validCoord(lat, lon)) {
            return Map.of();
        }
        List<CompletableFuture<Object[]>> fs = new ArrayList<>();
        for (int y = 1; y <= years; y++) {
            LocalDate start = today.minusYears(y);
            LocalDate end = start.plusDays(6);
            String url = archiveBase + "/v1/archive?latitude=" + c(lat) + "&longitude=" + c(lon) + "&start_date=" + start
                    + "&end_date=" + end + "&daily=temperature_2m_mean&timezone=auto";
            fs.add(CompletableFuture.supplyAsync(() -> new Object[]{start.getYear(), getMap(url)}));
        }
        Map<Integer, Double> out = new TreeMap<>();
        for (CompletableFuture<Object[]> f : fs) {
            try {
                Object[] r = f.get(12, java.util.concurrent.TimeUnit.SECONDS);
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) r[1];
                if (m.get("daily") instanceof Map<?, ?> d && d.get("temperature_2m_mean") instanceof List<?> l) {
                    double sum = 0;
                    int n = 0;
                    for (Object v : l) {
                        if (v instanceof Number x) {
                            sum += x.doubleValue();
                            n++;
                        }
                    }
                    if (n >= 5) {
                        out.put((Integer) r[0], sum / n);
                    }
                }
            } catch (Exception e) {
                // 그 해는 건너뛴다
            }
        }
        return out;
    }

    public Map<String, Object> airQuality(double lat, double lon) {
        if (!validCoord(lat, lon)) {
            return Map.of();
        }
        return getMap(airBase + "/v1/air-quality?latitude=" + c(lat) + "&longitude=" + c(lon)
                + "&current=pm10,pm2_5&hourly=pm2_5&forecast_days=2&timezone=auto");
    }

    // ───────── 경로 ─────────

    /** 장소 이름 → 좌표(한국 안에서만 찾는다). Nominatim 정책에 맞춰 1초에 1번만 부른다. */
    public Optional<Place> geocodePlace(String name) {
        if (name == null || name.isBlank() || name.length() > 80) {
            return Optional.empty();
        }
        synchronized (this) {
            long wait = 1100 - (System.currentTimeMillis() - lastNominatim);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
            lastNominatim = System.currentTimeMillis();
        }
        try {
            Object o = getJson(nominatimBase + "/search?q=" + q(name.strip()) + "&format=jsonv2&limit=1&countrycodes=kr&accept-language=ko",
                    Duration.ofSeconds(7));
            if (o instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
                double la = Double.parseDouble(String.valueOf(m.get("lat"))), lo = Double.parseDouble(String.valueOf(m.get("lon")));
                if (validCoord(la, lo)) {
                    return Optional.of(new Place(DeepDiveCards.clean(name, 30), la, lo));
                }
            }
        } catch (Exception e) {
            log.warn("[꼬리탐색] 장소 검색 실패 — {}", e.toString());
        }
        return Optional.empty();
    }

    /** 승용차 경로 후보(최대 3개). 도로 이름은 거리가 긴 순으로 3개를 등장 순서대로 붙인다. */
    public List<DeepDiveCards.Route> routes(Place from, Place to) {
        if (!validCoord(from.lat(), from.lon()) || !validCoord(to.lat(), to.lon())) {
            return List.of();
        }
        String url = osrmBase + "/route/v1/driving/" + String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f", from.lon(), from.lat(), to.lon(), to.lat())
                + "?alternatives=3&overview=false&steps=true";
        Map<String, Object> r = getMap(url);
        List<DeepDiveCards.Route> out = new ArrayList<>();
        if (r.get("routes") instanceof List<?> rs) {
            for (Object ro : rs) {
                if (out.size() >= 3 || !(ro instanceof Map<?, ?> m) || !(m.get("distance") instanceof Number d) || !(m.get("duration") instanceof Number t)) {
                    continue;
                }
                Map<String, Double> dist = new LinkedHashMap<>();
                if (m.get("legs") instanceof List<?> legs) {
                    for (Object lg : legs) {
                        if (lg instanceof Map<?, ?> lm && lm.get("steps") instanceof List<?> steps) {
                            for (Object st : steps) {
                                if (st instanceof Map<?, ?> sm && sm.get("name") instanceof String nm && !nm.isBlank() && sm.get("distance") instanceof Number sd) {
                                    dist.merge(DeepDiveCards.clean(nm, 20), sd.doubleValue(), Double::sum);
                                }
                            }
                        }
                    }
                }
                List<String> top = dist.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(3)
                        .map(Map.Entry::getKey).toList();
                List<String> ordered = new ArrayList<>();
                for (String name : dist.keySet()) {
                    if (top.contains(name)) {
                        ordered.add(name);
                    }
                }
                out.add(new DeepDiveCards.Route(d.doubleValue(), t.doubleValue(), ordered));
            }
        }
        return out;
    }
}
