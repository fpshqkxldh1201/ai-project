package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.gemini.GeminiContent;
import com.jarvisst.Lumi.dto.gemini.GeminiPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 꼬리 탐색용 "JSON으로만 답하는" 모델 호출. 예비 모델(OpenAI·Grok, 키가 있을 때)을 먼저 쓰고 안 되면 Gemini로 넘어간다 —
 * Gemini 한도를 대화 본답에 남겨 두기 위해서다. 둘 다 안 되면 Optional.empty()이고, 그 단계는 건너뛴다.
 *
 * 모델 출력은 믿지 않는다: JSON 객체 하나만 받아 파싱하고, 실패하면 버린다(호출하는 쪽이 다시 한 번 값을 거른다).
 */
@Service
public class DeepDiveLlm {

    private static final Logger log = LoggerFactory.getLogger(DeepDiveLlm.class);
    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*})\\s*```");

    private final RestClient gemini;
    private final String model;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private LlmFallbackService fallback;

    /** 시험에서 모델을 대신 끼워 넣는 자리. */
    interface Backend {
        Optional<String> ask(String system, String user);
    }

    private Backend testBackend;

    /** 시험에서 그림을 읽는 모델을 대신 끼워 넣는 자리. */
    interface ImageBackend {
        Optional<String> ask(String system, String user, String mime, String base64);
    }

    private ImageBackend testImageBackend;

    void setImageBackend(ImageBackend b) {
        this.testImageBackend = b;
    }

    void setBackend(Backend b) {
        this.testBackend = b;
    }

    public DeepDiveLlm(@Value("${gemini.api.base-url}") String baseUrl,
                       @Value("${gemini.model}") String model,
                       @Value("${gemini.api.key}") String apiKey) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.gemini = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.model = model;
        this.apiKey = apiKey;
    }

    public Optional<Map<String, Object>> json(String system, String user) {
        String raw = null;
        if (testBackend != null) {
            raw = testBackend.ask(system, user).orElse(null);
        } else {
            raw = viaFallback(system, user);
            if (raw == null || raw.isBlank()) {
                raw = viaGemini(system, user);
            }
        }
        return parse(raw);
    }

    /** 그림 한 장과 글을 함께 보내 JSON으로 받는다. 그림을 읽을 수 있는 Gemini만 쓴다(예비 모델 경로는 글 전용). */
    public Optional<Map<String, Object>> jsonWithImage(String system, String user, String mime, String base64) {
        String raw;
        if (testImageBackend != null) {
            raw = testImageBackend.ask(system, user, mime, base64).orElse(null);
        } else {
            raw = viaGeminiImage(system, user, mime, base64);
        }
        return parse(raw);
    }

    Optional<Map<String, Object>> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.strip();
        Matcher m = FENCE.matcher(s);
        if (m.find()) {
            s = m.group(1);
        } else {
            int a = s.indexOf('{'), b = s.lastIndexOf('}');
            if (a >= 0 && b > a) {
                s = s.substring(a, b + 1);
            }
        }
        try {
            Map<String, Object> out = mapper.readValue(s, new TypeReference<Map<String, Object>>() {
            });
            return out == null || out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (RuntimeException e) {
            log.warn("[꼬리탐색] 모델 JSON 해석 실패 — {}", e.toString());
            return Optional.empty();
        }
    }

    private String viaFallback(String system, String user) {
        LlmFallbackService fb = this.fallback;
        if (fb == null || !fb.isEnabled()) {
            return null;
        }
        try {
            GeminiContent c = new GeminiContent("user", List.of(GeminiPart.ofText(user)));
            return fb.chat(system, List.of(c), List.of(), null).orElse(null);
        } catch (RuntimeException e) {
            log.warn("[꼬리탐색] 예비 모델 실패 — {}", e.toString());
            return null;
        }
    }

    private String viaGemini(String system, String user) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
            body.put("generationConfig", Map.of("temperature", 0.3, "maxOutputTokens", 1600, "responseMimeType", "application/json"));
            Map<?, ?> response = gemini.post()
                    .uri("/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            StringBuilder out = new StringBuilder();
            if (response != null && response.get("candidates") instanceof List<?> cs && !cs.isEmpty()
                    && cs.get(0) instanceof Map<?, ?> cand && cand.get("content") instanceof Map<?, ?> content
                    && content.get("parts") instanceof List<?> parts) {
                for (Object p : parts) {
                    if (p instanceof Map<?, ?> pm && pm.get("text") instanceof String t) {
                        out.append(t);
                    }
                }
            }
            return out.toString();
        } catch (RuntimeException e) {
            log.warn("[꼬리탐색] Gemini 실패 — {}", e.toString());
            return null;
        }
    }

    private String viaGeminiImage(String system, String user, String mime, String base64) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", List.of(Map.of("role", "user", "parts", List.of(
                    Map.of("inlineData", Map.of("mimeType", mime, "data", base64)),
                    Map.of("text", user)))));
            body.put("generationConfig", Map.of("temperature", 0.2, "maxOutputTokens", 1200, "responseMimeType", "application/json"));
            Map<?, ?> response = gemini.post()
                    .uri("/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            StringBuilder out = new StringBuilder();
            if (response != null && response.get("candidates") instanceof List<?> cs && !cs.isEmpty()
                    && cs.get(0) instanceof Map<?, ?> cand && cand.get("content") instanceof Map<?, ?> content
                    && content.get("parts") instanceof List<?> parts) {
                for (Object p : parts) {
                    if (p instanceof Map<?, ?> pm && pm.get("text") instanceof String t) {
                        out.append(t);
                    }
                }
            }
            return out.toString();
        } catch (RuntimeException e) {
            log.warn("[화면분석] Gemini 실패 — {}", e.toString()); // 그림 내용은 로그에 남기지 않는다
            return null;
        }
    }
}
