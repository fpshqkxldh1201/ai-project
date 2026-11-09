package com.jarvisst.Lumi.config;

import com.jarvisst.Lumi.service.EmotionAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 로컬 실행 시 앱이 뜨자마자 한 번, 어떤 외부 연동이 설정/연결되어 있는지 로그로
 * 요약해서 보여준다. 이 프로젝트의 상당수 기능(웹검색, TTS, 감정 분석)이
 * "키가 없으면 그 기능만 조용히 비활성화"되는 방식이라, 콘솔에 에러가 안 찍혀도
 * 실제로는 절반만 켜져 있는 경우를 바로 알아채기 위한 용도다.
 */
@Component
public class StartupDiagnostics implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final String geminiApiKey;
    private final String naverClientId;
    private final String naverClientSecret;
    private final EmotionAnalysisService emotionAnalysisService;

    public StartupDiagnostics(@Value("${gemini.api.key}") String geminiApiKey,
                               @Value("${naver.api.client-id}") String naverClientId,
                               @Value("${naver.api.client-secret}") String naverClientSecret,
                               EmotionAnalysisService emotionAnalysisService) {
        this.geminiApiKey = geminiApiKey;
        this.naverClientId = naverClientId;
        this.naverClientSecret = naverClientSecret;
        this.emotionAnalysisService = emotionAnalysisService;
    }

    @Override
    public void run(ApplicationArguments args) {
        java.util.Map<String, Object> emotionHealth = emotionAnalysisService.health();
        boolean emotionReachable = emotionHealth != null;

        log.info("========================================================");
        log.info(" LUMI 로컬 실행 환경 점검");
        log.info("--------------------------------------------------------");
        log.info(" Gemini API 키 (대화·페르소나)      : {}", status(geminiApiKey));
        log.info(" Naver 검색 API 키 (web_search)      : {}", status(naverClientId, naverClientSecret));
        log.info(" TTS (브라우저 내장 speechSynthesis) : 항상 사용 가능 (키 불필요)");
        log.info(" 감정분석 서버 (localhost:8002) : {}",
                emotionReachable ? "연결됨" : "미연결 — 감정 배지 없이 대화만 동작");
        if (emotionReachable) {
            boolean gate = Boolean.TRUE.equals(emotionHealth.get("gate_enabled"));
            log.info(" 감정 모델 명령 게이트                : {}",
                    gate ? "켜짐 (일정·검색 같은 명령은 감정으로 읽지 않음)"
                            : "없음 — 명령 문장도 감정으로 읽힐 수 있음 (multilingual-e5-small 폴더에서 train.py를 다시 실행하세요)");
        }
        if (emotionReachable) {
            boolean intent = Boolean.TRUE.equals(emotionHealth.get("speaker_intent_enabled"));
            log.info(" 상대 발화 의도 분류기                : {}",
                    intent ? "켜짐 (상대 말 분석 사용 가능)"
                            : "없음 — python scripts/train_speaker_intent.py 로 학습한 뒤 serve.py를 다시 켜세요");
        }
        log.info("========================================================");

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.warn(" GEMINI_API_KEY가 없으면 대화 자체가 동작하지 않습니다. 반드시 설정하세요.");
        }
    }

    private String status(String value) {
        return (value == null || value.isBlank()) ? "미설정" : "설정됨";
    }

    private String status(String a, String b) {
        boolean ok = a != null && !a.isBlank() && b != null && !b.isBlank();
        return ok ? "설정됨" : "미설정";
    }
}
