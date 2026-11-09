package com.jarvisst.Lumi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 음성 인식(STT): 브라우저가 녹음해 보낸 짧은 음성을 OpenAI 전사 API로 텍스트로 바꾼다.
 *
 * - 키는 환경변수 OPENAI_API_KEY(예비 대화 모델과 같은 키)에서만 읽는다. 없으면 비활성(503)이고,
 *   프론트는 그러면 브라우저 내장 음성 인식으로 돌아간다.
 * - 업로드된 파일은 확장자·Content-Type을 믿지 않고 파일 맨 앞 바이트(매직 넘버)로 형식을 확인한다.
 * - 음성 파일은 저장하지 않는다(메모리에서 바로 전송하고 버린다). 인식된 글자만 돌려준다.
 * - 사용자별 분당 호출 수를 제한해 요금 폭주를 막는다.
 */
@Service
public class SttService {

    private static final Logger log = LoggerFactory.getLogger(SttService.class);

    /** 업로드 상한. 스프링 기본 multipart 한도(1MB)와 입구 필터(1MB) 안쪽으로 맞춘다. */
    public static final int MAX_AUDIO_BYTES = 900 * 1024;
    private static final int MIN_AUDIO_BYTES = 1500;
    private static final int MAX_TEXT_CHARS = 1000;
    private static final Set<String> LANGS = Set.of("ko", "en", "zh", "ja", "es");
    private static final long WINDOW_MS = 60_000L;

    public record Result(boolean ok, int status, String text, String error) {
        static Result ok(String text) {
            return new Result(true, 200, text, null);
        }

        static Result fail(int status, String error) {
            return new Result(false, status, null, error);
        }
    }

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int perMinute;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Duration readTimeout;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public SttService(
            @Value("${stt.enabled:true}") boolean enabled,
            @Value("${stt.openai.api-key:${llm.openai.api-key:${OPENAI_API_KEY:}}}") String apiKey,
            @Value("${stt.model:${OPENAI_STT_MODEL:gpt-4o-mini-transcribe}}") String model,
            @Value("${stt.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${stt.per-minute:20}") int perMinute,
            @Value("${stt.timeout-seconds:20}") int timeoutSeconds) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.perMinute = perMinute;
        this.readTimeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        log.info("[STT] 서버 음성 인식 {} (모델 {})", isAvailable() ? "사용 가능" : "꺼짐(키 미설정 또는 비활성)", model);
    }

    public boolean isAvailable() {
        return enabled && !apiKey.isBlank();
    }

    /** 파일 앞 바이트로 형식을 판별해 확장자를 돌려준다. 지원하지 않는 형식이면 null. */
    static String detectExtension(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "webm";
        }
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') {
            return "ogg";
        }
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "mp4";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V') {
            return "wav";
        }
        if ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0)) {
            return "mp3";
        }
        return null;
    }

    private boolean tryAcquire(String key) {
        if (hits.size() > 5_000) {
            hits.clear();
        }
        long now = System.currentTimeMillis();
        Deque<Long> dq = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) {
                dq.pollFirst();
            }
            if (dq.size() >= perMinute) {
                return false;
            }
            dq.addLast(now);
            return true;
        }
    }

    /** @param userKey 사용자 구분 키(로그인 이름 등) — 분당 호출 제한에 쓴다. */
    public Result transcribe(byte[] audio, String lang, String userKey) {
        if (!isAvailable()) {
            return Result.fail(503, "서버 음성 인식을 사용할 수 없습니다.");
        }
        if (audio == null || audio.length < MIN_AUDIO_BYTES) {
            return Result.fail(400, "음성이 너무 짧습니다.");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            return Result.fail(413, "음성이 너무 깁니다.");
        }
        String ext = detectExtension(audio);
        if (ext == null) {
            return Result.fail(400, "지원하지 않는 음성 형식입니다.");
        }
        if (!tryAcquire(userKey == null ? "anonymous" : userKey)) {
            return Result.fail(429, "음성 인식 요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        }
        String language = lang != null && LANGS.contains(lang.toLowerCase()) ? lang.toLowerCase() : null;

        String boundary = "----lumi" + UUID.randomUUID().toString().replace("-", "");
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1024);
            field(body, boundary, "model", model);
            field(body, boundary, "response_format", "json");
            if (language != null) {
                field(body, boundary, "language", language);
            }
            // 호출 이름을 잘 알아듣도록 짧은 힌트만 준다(대화 내용은 보내지 않는다).
            field(body, boundary, "prompt", "루미, Lumi");
            write(body, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"speech." + ext + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n");
            body.write(audio);
            write(body, "\r\n--" + boundary + "--\r\n");

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/audio/transcriptions"))
                    .timeout(readTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status / 100 != 2) {
                // 오류 본문에는 키 일부가 들어올 수 있어 상태 코드만 로그에 남긴다.
                log.warn("[STT] 전사 실패 — HTTP {}", status);
                return Result.fail(status == 429 ? 429 : 502, "음성 인식 서비스에서 오류가 났습니다.");
            }
            Map<String, Object> parsed = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            });
            Object t = parsed.get("text");
            String text = t == null ? "" : String.valueOf(t).trim();
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
            }
            return Result.ok(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(502, "음성 인식이 중단되었습니다.");
        } catch (Exception e) {
            log.warn("[STT] 전사 중 오류: {}", e.getClass().getSimpleName());
            return Result.fail(502, "음성 인식 중 오류가 났습니다.");
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws java.io.IOException {
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) throws java.io.IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
