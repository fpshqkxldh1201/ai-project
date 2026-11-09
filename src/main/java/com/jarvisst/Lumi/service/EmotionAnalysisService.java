package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.emotion.EmotionRequest;
import com.jarvisst.Lumi.dto.emotion.EmotionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Lumi가 직접 학습한 감정 분류 모델(multilingual-e5-small, FastAPI)을 호출한다.
 * 서버 주소는 emotion.server.base-url(기본 http://localhost:8002)로 바꿀 수 있다.
 */
@Service
public class EmotionAnalysisService {

    private final RestClient restClient;

    public EmotionAnalysisService(@Value("${emotion.server.base-url:http://localhost:8002}") String baseUrl) {
        // 감정 분석은 감정 배지·답변 톤용 부가기능일 뿐이라 느리거나 꺼져 있어도 /api/chat 전체가
        // 덩달아 느려지면 안 된다 — 연결 1.5초, 응답 3초로 짧게 끊고 바로 null로 폴백한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * 감정분석 서버의 /health로 짧게 핑을 보내 지금 떠 있는지 확인한다.
     * 로컬 실행 시 진단 로그(StartupDiagnostics)에서만 사용하며, 실패해도 예외를
     * 던지지 않고 false만 반환한다.
     */
    public boolean isReachable() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 감정분석 서버 /health 응답(model_version, gate_enabled 등). 서버가 꺼져 있으면 null.
     * 시작 점검 로그에서 "명령 게이트(명령 문장을 감정으로 읽지 않게 하는 장치)가 켜져 있는지" 보여 주는 데만 쓴다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        try {
            return restClient.get().uri("/health").retrieve().body(Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 사용자 메시지의 감정을 분석한다.
     * 서버가 꺼져 있거나 오류가 나도 대화 자체(/api/chat)는 막지 않도록 null을 반환한다.
     */
    public EmotionResult analyzeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return post(EmotionRequest.ofText(text));
    }

    /**
     * 사진의 표정에서 감정을 분석한다(사진은 감정 분석에만 쓰이고 저장하지 않는다).
     * 서버가 꺼져 있거나 사진 분석이 꺼져 있으면(501) 대화를 막지 않도록 null을 반환한다.
     */
    public EmotionResult analyzeImage(String base64Jpeg) {
        if (base64Jpeg == null || base64Jpeg.isBlank()) {
            return null;
        }
        return post(EmotionRequest.ofImageData(base64Jpeg));
    }

    /** 글과 사진을 함께 분석한다(둘 다 있을 때만). 실패하면 null. */
    public EmotionResult analyzeBoth(String text, String base64Jpeg) {
        if (text == null || text.isBlank() || base64Jpeg == null || base64Jpeg.isBlank()) {
            return null;
        }
        return post(EmotionRequest.ofBoth(text, base64Jpeg));
    }

    private EmotionResult post(EmotionRequest request) {
        try {
            return restClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(EmotionResult.class);
        } catch (Exception e) {
            return null;
        }
    }
}
