package com.jarvisst.Lumi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 개념 설명 카드에 붙일 그림·요약을 위키백과에서 가져온다.
 *
 * 보안·안전
 *  - 접속하는 곳은 {ko,en,ja,zh,es}.wikipedia.org 와 그림 서버 upload.wikimedia.org 로 고정이다(사용자·모델이 준 주소는 쓰지 않는다 — SSRF 방지).
 *  - 브라우저는 외부 주소가 아니라 우리 서버의 /api/media/wiki/{id} 만 부른다. id는 무작위이고 30분 뒤 사라진다.
 *  - 그림은 png·jpeg·gif·webp 만, 2MB 이하만 통과시킨다(svg는 스크립트가 들어갈 수 있어 막는다).
 *  - 못 가져오면(없는 문서·동음이의어·시간 초과) 조용히 비워 둔다. 카드는 그림 없이도 완성된다.
 */
@Service
public class ConceptMediaService {

    private static final Logger log = LoggerFactory.getLogger(ConceptMediaService.class);

    private static final String UA = "Lumi-AI-Project/1.0 (university course project; Wikipedia REST client)";
    private static final String IMAGE_HOST = "upload.wikimedia.org";
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_ENTRIES = 200;
    private static final Map<String, String> HOSTS = Map.of(
            "ko", "ko.wikipedia.org", "en", "en.wikipedia.org", "ja", "ja.wikipedia.org",
            "zh", "zh.wikipedia.org", "es", "es.wikipedia.org");
    private static final Pattern THUMB_SIZE = Pattern.compile("/(\\d{2,4})px-([^/]+)$");

    /** 위키백과 문서 한 건. image는 우리 프록시 id가 아니라 원본 썸네일 주소(내부용). */
    public record WikiInfo(String title, String extract, String thumbUrl, String pageUrl) {
    }

    public record ImageBytes(byte[] bytes, String contentType) {
    }

    /** 시험용: 값이 있으면 위키백과 대신 이 주소(예: http://127.0.0.1:18778)를 쓴다. 운영에서는 항상 null. */
    String baseOverride;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, String[]> registry = new LinkedHashMap<>(); // id -> {url, expiresAtMs}

    // ───────────────────── 위키백과 조회 ─────────────────────

    /** term(개념 이름)에 대응하는 위키백과 문서. 못 찾거나 엉뚱해 보이면 empty. */
    public Optional<WikiInfo> lookup(String term, String lang) {
        String host = HOSTS.get(lang == null ? "ko" : lang);
        String q = term == null ? "" : term.replaceAll("[\\p{Cntrl}\"<>]", " ").strip();
        if (host == null || q.isBlank() || q.length() > 60) {
            return Optional.empty();
        }
        try {
            WikiInfo info = summary(host, q);
            if (info == null) {
                String key = searchTitle(host, q);
                info = key == null ? null : summary(host, key);
            }
            if (info == null || !related(q, info.title())) {
                return Optional.empty();
            }
            return Optional.of(info);
        } catch (Exception e) {
            log.info("[개념 카드] 위키백과 조회 실패(무시): {}", e.toString());
            return Optional.empty();
        }
    }

    /** 검색어와 문서 제목이 서로 포함 관계일 때만 같은 주제로 본다(엉뚱한 문서를 붙이지 않기 위해). */
    static boolean related(String query, String pageTitle) {
        String a = norm(query), b = norm(pageTitle);
        return !a.isEmpty() && !b.isEmpty() && (b.contains(a) || a.contains(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-·()（）]", "");
    }

    private WikiInfo summary(String host, String title) throws Exception {
        String enc = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
        String body = get(base(host) + "/api/rest_v1/page/summary/" + enc);
        if (body == null) {
            return null;
        }
        Map<String, Object> m = mapper.readValue(body, new TypeReference<Map<String, Object>>() { });
        String type = String.valueOf(m.get("type"));
        if (type.contains("disambiguation") || "no-extract".equals(type)) {
            return null;
        }
        String extract = m.get("extract") instanceof String s ? s.strip() : "";
        if (extract.length() < 40) {
            return null;
        }
        String thumb = null;
        if (m.get("thumbnail") instanceof Map<?, ?> t && t.get("source") instanceof String src) {
            thumb = upsize(src);
        }
        String page = null;
        if (m.get("content_urls") instanceof Map<?, ?> cu && cu.get("desktop") instanceof Map<?, ?> d
                && d.get("page") instanceof String p && (p.startsWith("https://" + host + "/") || baseOverride != null)) {
            page = p;
        }
        String shownTitle = m.get("title") instanceof String s2 ? s2 : title;
        return new WikiInfo(shownTitle, extract, thumb, page);
    }

    private String searchTitle(String host, String q) throws Exception {
        String enc = URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20");
        String body = get(base(host) + "/w/rest.php/v1/search/title?limit=1&q=" + enc);
        if (body == null) {
            return null;
        }
        Map<String, Object> m = mapper.readValue(body, new TypeReference<Map<String, Object>>() { });
        if (m.get("pages") instanceof List<?> pages && !pages.isEmpty() && pages.get(0) instanceof Map<?, ?> p
                && p.get("title") instanceof String title) {
            return title;
        }
        return null;
    }

    /** 320px 썸네일 주소를 640px로 키운다(위키 썸네일 주소 규칙). 규칙에 안 맞으면 그대로. */
    static String upsize(String src) {
        Matcher m = THUMB_SIZE.matcher(src);
        return m.find() ? src.substring(0, m.start()) + "/640px-" + m.group(2) : src;
    }

    private String base(String host) {
        return baseOverride != null ? baseOverride : "https://" + host;
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(2500))
                .header("User-Agent", UA).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? res.body() : null;
    }

    // ───────────────────── 그림 프록시 ─────────────────────

    /** 위키 그림 주소를 등록하고 브라우저가 부를 우리 서버 주소(/api/media/wiki/{id})를 돌려준다. 허용 호스트가 아니면 null. */
    public String register(String upstreamUrl) {
        if (!allowedImageUrl(upstreamUrl)) {
            return null;
        }
        String id = HexFormat.of().formatHex(randomBytes(12));
        long now = System.currentTimeMillis();
        synchronized (registry) {
            registry.values().removeIf(v -> Long.parseLong(v[1]) < now);
            while (registry.size() >= MAX_ENTRIES) {
                registry.remove(registry.keySet().iterator().next());
            }
            registry.put(id, new String[]{upstreamUrl, String.valueOf(now + TTL_MS)});
        }
        return "/api/media/wiki/" + id;
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    static boolean allowedImageUrl(String url) {
        try {
            URI u = URI.create(url);
            return "https".equals(u.getScheme()) && IMAGE_HOST.equalsIgnoreCase(u.getHost()) && u.getUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443);
        } catch (Exception e) {
            return false;
        }
    }

    /** 등록된 id의 그림을 받아 온다. 만료·미등록·크기 초과·허용되지 않는 형식이면 empty. */
    public Optional<ImageBytes> fetch(String id) {
        if (id == null || !id.matches("[0-9a-f]{24}")) {
            return Optional.empty();
        }
        String url;
        synchronized (registry) {
            String[] v = registry.get(id);
            if (v == null || Long.parseLong(v[1]) < System.currentTimeMillis()) {
                return Optional.empty();
            }
            url = v[0];
        }
        if (!allowedImageUrl(url)) {
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                    .header("User-Agent", UA).GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                return Optional.empty();
            }
            String type = res.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi >= 0) {
                type = type.substring(0, semi).strip();
            }
            if (!List.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(type)) {
                return Optional.empty();
            }
            try (InputStream in = res.body()) {
                byte[] data = in.readNBytes(MAX_IMAGE_BYTES + 1);
                return data.length > MAX_IMAGE_BYTES || data.length == 0 ? Optional.empty() : Optional.of(new ImageBytes(data, type));
            }
        } catch (Exception e) {
            log.info("[개념 카드] 그림 받기 실패(무시): {}", e.toString());
            return Optional.empty();
        }
    }
}
