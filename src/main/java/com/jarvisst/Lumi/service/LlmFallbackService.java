package com.jarvisst.Lumi.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.jarvisst.Lumi.dto.gemini.GeminiContent;
import com.jarvisst.Lumi.dto.gemini.GeminiFunctionCall;
import com.jarvisst.Lumi.dto.gemini.GeminiFunctionDeclaration;
import com.jarvisst.Lumi.dto.gemini.GeminiPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 예비 대화 모델. 주 모델(Gemini)이 한도 초과(429)·과부하(503)·시간 초과 등으로 실패했을 때,
 * OpenAI 호환 Chat Completions API(OpenAI, xAI Grok)로 같은 대화를 이어서 답한다.
 *
 * <ul>
 *   <li>같은 시스템 프롬프트·같은 대화·같은 도구(일정/검색/그래프 등)를 쓰고, 도구는 Lumi 서버가 그대로 실행한다.</li>
 *   <li>예비 모델에는 Gemini에 보내던 것과 같은, 이미 민감정보가 가려진 텍스트만 간다(이미지는 원래 보내지 않는다).</li>
 *   <li>키는 설정 파일이 아니라 환경변수(OPENAI_API_KEY, XAI_API_KEY)로만 받는다. 로그에는 키·대화 내용을 남기지 않는다.</li>
 *   <li>제공자 순서는 llm.fallback.order(기본 openai,grok). 앞 제공자가 실패하면 같은 대화로 다음 제공자를 시도한다.
 *       이미 실행한 도구(일정 추가 등)는 다시 실행하지 않는다.</li>
 * </ul>
 */
@Service
public class LlmFallbackService {

    private static final Logger log = LoggerFactory.getLogger(LlmFallbackService.class);
    private static final int MAX_TURNS = 5;
    private static final int MAX_TOOL_RESULT_CHARS = 6000;
    private static final int MAX_ERROR_CHARS = 200;

    /** 도구 실행기: 함수 이름과 인자를 받아 결과를 돌려준다(Lumi 서버가 구현). */
    @FunctionalInterface
    public interface ToolExecutor {
        Map<String, Object> execute(String name, Map<String, Object> args);
    }

    /** 제공자 응답이 2xx가 아닐 때. */
    static final class LlmHttpException extends RuntimeException {
        final int status;

        LlmHttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    record Provider(String name, String baseUrl, String apiKey, String model) {
        boolean usable() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank()
                    && baseUrl != null && !baseUrl.isBlank();
        }
    }

    private final boolean enabled;
    private final List<Provider> providers;
    private final HttpClient http;
    private final Duration readTimeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmFallbackService(
            @Value("${llm.fallback.enabled:true}") boolean enabled,
            @Value("${llm.fallback.order:openai,grok}") String order,
            @Value("${llm.fallback.timeout-seconds:40}") int timeoutSeconds,
            @Value("${llm.openai.api-key:${OPENAI_API_KEY:}}") String openaiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String openaiBase,
            @Value("${llm.openai.model:${OPENAI_MODEL:gpt-4o-mini}}") String openaiModel,
            @Value("${llm.grok.api-key:${XAI_API_KEY:}}") String grokKey,
            @Value("${llm.grok.base-url:https://api.x.ai/v1}") String grokBase,
            @Value("${llm.grok.model:${XAI_MODEL:grok-4-1-fast}}") String grokModel) {
        this.enabled = enabled;
        Map<String, Provider> all = Map.of(
                "openai", new Provider("openai", trimSlash(openaiBase), openaiKey, openaiModel),
                "grok", new Provider("grok", trimSlash(grokBase), grokKey, grokModel));
        List<Provider> chosen = new ArrayList<>();
        for (String name : order.split(",")) {
            Provider p = all.get(name.trim().toLowerCase());
            if (p != null && p.usable() && chosen.stream().noneMatch(c -> c.name().equals(p.name()))) {
                chosen.add(p);
            }
        }
        this.providers = List.copyOf(chosen);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.readTimeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        log.info("[예비 모델] {} — 사용 가능한 제공자: {}", enabled ? "켜짐" : "꺼짐",
                providers.isEmpty() ? "없음(키 미설정)" : providers.stream().map(p -> p.name() + "(" + p.model() + ")").toList());
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    public boolean isEnabled() {
        return enabled && !providers.isEmpty();
    }

    public List<String> providerNames() {
        return providers.stream().map(Provider::name).toList();
    }

    /**
     * 같은 대화를 예비 모델로 이어서 답한다. 도구 호출이 나오면 executor로 실행해 결과를 돌려주며 최대 MAX_TURNS번 왕복한다.
     * 모든 제공자가 실패하거나 빈 답이면 Optional.empty().
     */
    public Optional<String> chat(String systemPrompt, List<GeminiContent> contents,
                                 List<GeminiFunctionDeclaration> declarations, ToolExecutor executor) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        List<Map<String, Object>> messages = toMessages(systemPrompt, contents);
        List<Map<String, Object>> tools = toTools(declarations);
        int[] cursor = {0}; // 지금 쓰는 제공자(실패하면 다음으로 넘어가고, 같은 요청 안에서는 앞으로 되돌아가지 않는다)

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            Map<String, Object> message = post(messages, tools, cursor);
            if (message == null) {
                return Optional.empty();
            }
            List<Map<String, Object>> toolCalls = toolCallsOf(message);
            if (toolCalls.isEmpty()) {
                Object content = message.get("content");
                String text = content instanceof String s ? s.trim() : "";
                return text.isEmpty() ? Optional.empty() : Optional.of(text);
            }
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", message.get("content") instanceof String s && !s.isBlank() ? s : null);
            assistant.put("tool_calls", toolCalls);
            messages.add(assistant);
            for (Map<String, Object> call : toolCalls) {
                messages.add(toolMessage(call, executor));
            }
        }
        log.warn("[예비 모델] 도구 호출이 {}번을 넘겨 중단", MAX_TURNS);
        return Optional.empty();
    }

    // ── 제공자 호출 ──

    private Map<String, Object> post(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int[] cursor) {
        for (; cursor[0] < providers.size(); cursor[0]++) {
            Provider p = providers.get(cursor[0]);
            long startedAt = System.nanoTime();
            try {
                Map<String, Object> message = callProvider(p, messages, tools);
                log.info("[예비 모델] {} 응답 — {}ms", p.name(), (System.nanoTime() - startedAt) / 1_000_000);
                return message;
            } catch (LlmHttpException e) {
                log.warn("[예비 모델] {} 실패 — HTTP {} ({}ms): {}", p.name(), e.status,
                        (System.nanoTime() - startedAt) / 1_000_000, e.getMessage());
            } catch (Exception e) {
                log.warn("[예비 모델] {} 실패 — {}ms: {}", p.name(), (System.nanoTime() - startedAt) / 1_000_000, e.toString());
            }
        }
        return null;
    }

    private Map<String, Object> callProvider(Provider p, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", p.model());
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(p.baseUrl() + "/chat/completions"))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + p.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new LlmHttpException(response.statusCode(), errorSummary(response.body()));
        }
        Map<String, Object> parsed = mapper.readValue(response.body(), new TypeReference<>() {
        });
        if (parsed.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice && choice.get("message") instanceof Map<?, ?> msg) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) msg;
            return typed;
        }
        throw new IllegalStateException("응답에 choices[0].message가 없음");
    }

    /** 제공자의 오류 본문에서 오류 종류만 짧게 뽑는다(대화 내용이나 키가 로그에 남지 않도록 길이 제한). */
    private String errorSummary(String body) {
        try {
            Map<String, Object> m = mapper.readValue(body, new TypeReference<>() {
            });
            Object err = m.get("error");
            if (err instanceof Map<?, ?> em && em.get("message") != null) {
                return cut(String.valueOf(em.get("message")));
            }
            if (err != null) {
                return cut(String.valueOf(err));
            }
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 아래 기본 문구
        }
        return "오류 본문을 읽지 못함";
    }

    private static String cut(String s) {
        return s.length() > MAX_ERROR_CHARS ? s.substring(0, MAX_ERROR_CHARS) : s;
    }

    // ── Gemini 형식 ↔ OpenAI 형식 변환 ──

    List<Map<String, Object>> toMessages(String systemPrompt, List<GeminiContent> contents) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        java.util.ArrayDeque<String> pendingIds = new java.util.ArrayDeque<>();
        int seq = 0;
        for (GeminiContent content : contents) {
            List<GeminiPart> parts = content.parts() == null ? List.of() : content.parts();
            if ("model".equals(content.role())) {
                StringBuilder text = new StringBuilder();
                List<Map<String, Object>> calls = new ArrayList<>();
                for (GeminiPart part : parts) {
                    if (part.text() != null) {
                        text.append(part.text());
                    }
                    GeminiFunctionCall fc = part.functionCall();
                    if (fc != null) {
                        String id = "call_" + (++seq);
                        pendingIds.add(id);
                        calls.add(toolCall(id, fc.name(), toJson(fc.args() == null ? Map.of() : fc.args())));
                    }
                }
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", text.length() == 0 ? null : text.toString());
                if (!calls.isEmpty()) {
                    assistant.put("tool_calls", calls);
                }
                messages.add(assistant);
            } else {
                StringBuilder text = new StringBuilder();
                for (GeminiPart part : parts) {
                    if (part.functionResponse() != null) {
                        Map<String, Object> tool = new LinkedHashMap<>();
                        tool.put("role", "tool");
                        tool.put("tool_call_id", pendingIds.isEmpty() ? "call_" + (++seq) : pendingIds.poll());
                        tool.put("content", limit(toJson(part.functionResponse().response())));
                        messages.add(tool);
                    } else if (part.text() != null) {
                        if (text.length() > 0) {
                            text.append("\n\n");
                        }
                        text.append(part.text());
                    }
                }
                if (text.length() > 0) {
                    messages.add(msg("user", text.toString()));
                }
            }
        }
        return messages;
    }

    List<Map<String, Object>> toTools(List<GeminiFunctionDeclaration> declarations) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (declarations == null) {
            return tools;
        }
        for (GeminiFunctionDeclaration d : declarations) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", d.name());
            fn.put("description", d.description());
            fn.put("parameters", lowerTypes(d.parameters()));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    /** Gemini 스키마의 type("OBJECT", "STRING" …)을 JSON Schema 표기(소문자)로 바꾼다. */
    @SuppressWarnings("unchecked")
    static Object lowerTypes(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object v = e.getValue();
                out.put(key, "type".equals(key) && v instanceof String s ? s.toLowerCase() : lowerTypes(v));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(lowerTypes(o));
            }
            return out;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolCallsOf(Map<String, Object> message) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    calls.add((Map<String, Object>) m);
                }
            }
        }
        return calls;
    }

    private Map<String, Object> toolMessage(Map<String, Object> call, ToolExecutor executor) {
        String id = String.valueOf(call.get("id"));
        String name = "";
        Map<String, Object> args = Map.of();
        if (call.get("function") instanceof Map<?, ?> fn) {
            name = String.valueOf(fn.get("name"));
            if (fn.get("arguments") instanceof String raw && !raw.isBlank()) {
                try {
                    args = mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception e) {
                    args = Map.of();
                }
            }
        }
        Map<String, Object> result;
        try {
            result = executor.execute(name, args);
        } catch (Exception e) {
            log.warn("[예비 모델] 도구 {} 실행 실패: {}", name, e.toString());
            result = Map.of("error", "도구를 실행하지 못했습니다.");
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("role", "tool");
        tool.put("tool_call_id", id);
        tool.put("content", limit(toJson(result)));
        return tool;
    }

    private static Map<String, Object> toolCall(String id, String name, String arguments) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", fn);
        return call;
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String limit(String s) {
        return s.length() > MAX_TOOL_RESULT_CHARS ? s.substring(0, MAX_TOOL_RESULT_CHARS) : s;
    }
}
