package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.naver.NaverSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class NaverSearchService {

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public NaverSearchService(@Value("${naver.api.client-id}") String clientId,
                               @Value("${naver.api.client-secret}") String clientSecret) {
        // 네이버 검색도 제한시간이 없으면 느려졌을 때 Gemini 함수 호출 전체가 같이 묶여서
        // 늘어진다 — 연결 3초, 응답 5초로 상한을 둔다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl("https://naverapihub.apigw.ntruss.com")
                .requestFactory(requestFactory)
                .build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    private boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * NAVER API HUB의 웹문서 검색으로 상위 결과를 조회한다. 제목/설명에 섞여오는 <b> 태그와
     * HTML 엔티티는 걷어내서 Gemini에 깔끔한 텍스트로 전달한다.
     * (2026-07-31부로 옛 개발자센터(openapi.naver.com)는 검색 API 신규발급을 막았고,
     * NAVER Cloud Platform의 NAVER API HUB로 이전됨 — 엔드포인트/인증 헤더가 다르다.)
     */
    public Map<String, Object> search(String query, int display) {
        return request("/search/v1/webkr", query, display, null);
    }

    /**
     * 뉴스 검색(/search/v1/news). 최신 사실 확인이 목적이라 최신순(date)으로 가져오고,
     * 각 결과에 발행 시각(pubDate)을 함께 돌려준다.
     */
    public Map<String, Object> searchNews(String query, int display) {
        return request("/search/v1/news", query, display, "date");
    }

    private Map<String, Object> request(String path, String query, int display, String sort) {
        if (!isConfigured()) {
            return Map.of("error", "네이버 검색 API 키(NAVER_CLIENT_ID/NAVER_CLIENT_SECRET)가 설정되지 않았습니다.");
        }
        int size = Math.max(1, Math.min(display, 5));

        try {
            String rawBody = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path)
                                .queryParam("query", query)
                                .queryParam("display", size);
                        if (sort != null) {
                            uriBuilder.queryParam("sort", sort);
                        }
                        return uriBuilder.build();
                    })
                    .header("X-NCP-APIGW-API-KEY-ID", clientId)
                    .header("X-NCP-APIGW-API-KEY", clientSecret)
                    .retrieve()
                    .body(String.class);
            // NAVER API HUB는 JSON을 text/plain 으로 내려보낼 때가 있어 자동 변환이 실패한다 — 문자열로 받아 직접 해석.
            NaverSearchResponse response = parseNaver(rawBody);

            if (response == null || response.items() == null || response.items().isEmpty()) {
                return Map.of("results", List.of(), "message", "검색 결과가 없습니다.");
            }

            List<Map<String, String>> results = response.items().stream()
                    .map(item -> Map.of(
                            "title", stripTags(item.title()),
                            "link", item.link() != null ? item.link() : "",
                            "description", stripTags(item.description()),
                            "pubDate", item.pubDate() != null ? item.pubDate() : ""
                    ))
                    .toList();

            return Map.of("results", results);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(NaverSearchService.class)
                    .warn("[NAVER 검색] 실패 path={}, 원인={}", path, e.toString());
            return Map.of("error", "웹 검색 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private static final tools.jackson.databind.ObjectMapper NAVER_MAPPER = new tools.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private static NaverSearchResponse parseNaver(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.Map<String, Object> root = NAVER_MAPPER.readValue(body,
                new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
        Object items = root.get("items");
        if (!(items instanceof List<?> list)) {
            return null;
        }
        List<NaverSearchResponse.Item> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof java.util.Map<?, ?> m) {
                out.add(new NaverSearchResponse.Item(str(m.get("title")), str(m.get("link")),
                        str(m.get("description")), str(m.get("pubDate"))));
            }
        }
        return new NaverSearchResponse(out);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String stripTags(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]*>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
