package com.jarvisst.Lumi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Fish Audio TTS. 이 서비스가 null을 돌려주는 건 "키 없음 / 크레딧·인증 오류 / 재시도해도 계속 실패"일 때뿐이다.
 * 이전에는 실패 원인을 전혀 남기지 않아서 503만 보였는데, 지금은 원인(HTTP 상태·본문)을 로그에 남기고,
 * 일시 오류(429·5xx·시간 초과)는 다시 시도하며, 긴 답변은 문장 단위로 나눠 합성한다(시간 초과 방지).
 */
@Service
public class FishAudioTtsService {

    private static final Logger log = LoggerFactory.getLogger(FishAudioTtsService.class);

    private static final int CHUNK_MAX = 280;
    private static final int ATTEMPTS = 3;
    private static final int CACHE_MAX = 24;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String defaultReferenceId;
    private final String koMaleReferenceId;
    private final String koFemaleReferenceId;
    private final String enMaleReferenceId;
    private final String enFemaleReferenceId;

    /** 동시에 Fish Audio를 부르는 수를 제한한다(무료 등급은 동시 요청이 많으면 429가 난다). */
    private final Semaphore gate = new Semaphore(2);

    /** 같은 문장을 다시 읽을 때(자동재생 차단 후 재생 등) 크레딧을 또 쓰지 않도록 최근 합성본을 잠깐 보관한다. */
    private final Map<String, byte[]> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_MAX;
        }
    };

    public FishAudioTtsService(@Value("${fish.audio.api-key}") String apiKey,
                                @Value("${fish.audio.model}") String model,
                                @Value("${fish.audio.reference-id}") String defaultReferenceId,
                                @Value("${fish.audio.reference-id.ko-male}") String koMaleReferenceId,
                                @Value("${fish.audio.reference-id.ko-female}") String koFemaleReferenceId,
                                @Value("${fish.audio.reference-id.en-male}") String enMaleReferenceId,
                                @Value("${fish.audio.reference-id.en-female}") String enFemaleReferenceId) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(25));
        this.restClient = RestClient.builder()
                .baseUrl("https://api.fish.audio")
                .requestFactory(requestFactory)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.defaultReferenceId = defaultReferenceId;
        this.koMaleReferenceId = koMaleReferenceId;
        this.koFemaleReferenceId = koFemaleReferenceId;
        this.enMaleReferenceId = enMaleReferenceId;
        this.enFemaleReferenceId = enFemaleReferenceId;
        if (isConfigured()) {
            log.info("[TTS] Fish Audio 켜짐 — 모델={}", (model == null || model.isBlank()) ? "s1" : model);
        } else {
            log.warn("[TTS] Fish Audio 키(FISH_API_KEY)가 비어 있습니다 — 음성이 나오지 않습니다");
        }
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 텍스트를 음성(mp3)으로 합성한다. 실패하면 null(컨트롤러가 503) — 프론트는 브라우저 목소리로 바꾸지 않고
     * 안내만 띄운다. 원인은 "[TTS] Fish Audio 실패 …" 로그에 남는다.
     */
    public byte[] synthesize(String text, String language, String gender) {
        if (!isConfigured() || text == null || text.isBlank()) {
            if (!isConfigured()) {
                log.warn("[TTS] 요청이 왔지만 Fish Audio 키가 없습니다");
            }
            return null;
        }
        String referenceId = resolveReferenceId(language, gender);
        String cacheKey = (referenceId == null ? "" : referenceId) + "|" + text;
        synchronized (cache) {
            byte[] hit = cache.get(cacheKey);
            if (hit != null) {
                return hit;
            }
        }
        boolean acquired = false;
        try {
            acquired = gate.tryAcquire(20, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[TTS] 동시 합성 대기가 20초를 넘겨 포기");
                return null;
            }
            long startedAt = System.nanoTime();
            byte[] audio = TtsPipeline.run(text, CHUNK_MAX, ATTEMPTS, chunk -> post(chunk, referenceId),
                    ms -> {
                        try {
                            Thread.sleep(ms);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, log);
            if (audio != null) {
                log.info("[TTS] 합성 완료 — {}자, {}바이트, {}ms", text.length(), audio.length,
                        (System.nanoTime() - startedAt) / 1_000_000);
                synchronized (cache) {
                    cache.put(cacheKey, audio);
                }
            }
            return audio;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    }

    private byte[] post(String chunk, String referenceId) throws TtsPipeline.HttpFailure {
        Map<String, Object> body = new HashMap<>();
        body.put("text", chunk);
        body.put("format", "mp3");
        if (referenceId != null && !referenceId.isBlank()) {
            body.put("reference_id", referenceId);
        }
        try {
            return restClient.post()
                    .uri("/v1/tts")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("model", (model == null || model.isBlank()) ? "s1" : model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            String b = e.getResponseBodyAsString();
            throw new TtsPipeline.HttpFailure(e.getStatusCode().value(),
                    b == null ? "" : (b.length() > 200 ? b.substring(0, 200) : b), e);
        } catch (Exception e) {
            throw new TtsPipeline.HttpFailure(0, e.toString(), e);
        }
    }

    /**
     * (언어, 성별) 조합에 맞는 Fish Audio voice ID를 고른다. 한국어/영어 + 남성/여성 4가지 조합을 환경변수로
     * 개별 지정할 수 있고, 해당 조합이 비어 있으면 공용 기본 voice ID(fish.audio.reference-id)로 내려간다.
     */
    private String resolveReferenceId(String language, String gender) {
        boolean male = "male".equalsIgnoreCase(gender);
        String specific = switch (language == null ? "ko" : language) {
            case "en" -> male ? enMaleReferenceId : enFemaleReferenceId;
            default -> male ? koMaleReferenceId : koFemaleReferenceId;
        };
        if (specific != null && !specific.isBlank()) {
            return specific;
        }
        return defaultReferenceId;
    }
}
