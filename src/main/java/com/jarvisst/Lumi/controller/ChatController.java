package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.chat.ChatRequest;
import com.jarvisst.Lumi.dto.chat.ImageChatRequest;
import com.jarvisst.Lumi.dto.chat.TranslateRequest;
import com.jarvisst.Lumi.dto.chat.ChatResponse;
import com.jarvisst.Lumi.dto.chat.ChatResult;
import com.jarvisst.Lumi.dto.emotion.EmotionResult;
import com.jarvisst.Lumi.entity.SecurityEvent;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.security.ChatRateLimiter;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import com.jarvisst.Lumi.security.EmotionSanitizer;
import com.jarvisst.Lumi.security.InFlightLimiter;
import com.jarvisst.Lumi.security.InjectionStrikeTracker;
import com.jarvisst.Lumi.security.ImageSanitizer;
import com.jarvisst.Lumi.security.InputSanitizer;
import com.jarvisst.Lumi.security.SecurityEventService;
import com.jarvisst.Lumi.security.SensitiveDataMasker;
import com.jarvisst.Lumi.service.GeminiService;
import com.jarvisst.Lumi.service.LastExchangeStore;
import com.jarvisst.Lumi.service.TranslationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jarvisst.Lumi.dto.chat.ResultCard;
import com.jarvisst.Lumi.service.ResultCardBuilder;
import com.jarvisst.Lumi.service.ResultCardContext;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final GeminiService geminiService;
    private final CurrentUserProvider currentUserProvider;
    private final ChatRateLimiter chatRateLimiter;
    private final LastExchangeStore lastExchangeStore;
    private final InputSanitizer inputSanitizer;
    private final InjectionStrikeTracker strikeTracker;
    private final SecurityEventService securityEvents;
    private final InFlightLimiter inFlightLimiter;
    private final SensitiveDataMasker sensitiveDataMasker;
    private final ImageSanitizer imageSanitizer;
    private final TranslationService translationService;

    public ChatController(GeminiService geminiService, CurrentUserProvider currentUserProvider,
                          ChatRateLimiter chatRateLimiter, LastExchangeStore lastExchangeStore,
                          InputSanitizer inputSanitizer, InjectionStrikeTracker strikeTracker,
                          SecurityEventService securityEvents, InFlightLimiter inFlightLimiter,
                          SensitiveDataMasker sensitiveDataMasker, ImageSanitizer imageSanitizer,
                          TranslationService translationService) {
        this.geminiService = geminiService;
        this.currentUserProvider = currentUserProvider;
        this.chatRateLimiter = chatRateLimiter;
        this.lastExchangeStore = lastExchangeStore;
        this.inputSanitizer = inputSanitizer;
        this.strikeTracker = strikeTracker;
        this.securityEvents = securityEvents;
        this.inFlightLimiter = inFlightLimiter;
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.imageSanitizer = imageSanitizer;
        this.translationService = translationService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest request) {
        return process(request.message(), request.responseLanguage(), null);
    }

    /** 이미지가 붙은 대화. 이미지는 서버가 검증하고 JPEG로 다시 만든 뒤에만 Gemini로 보낸다(저장하지 않음). */
    @PostMapping("/image")
    public ResponseEntity<?> chatWithImage(@Valid @RequestBody ImageChatRequest request) {
        ImageSanitizer.SanitizedImage image;
        try {
            image = imageSanitizer.sanitize(request.imageBase64(), request.imageMime());
        } catch (ImageSanitizer.InvalidImageException e) {
            securityEvents.record(SecurityEvent.Type.IMAGE_REJECTED, "이미지 거부: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
        return process(request.message() == null ? "" : request.message(), request.responseLanguage(), image);
    }

    /**
     * 방금 받은 외국어 답변의 한국어 번역. 서버가 사용자에게 준 최근 답변과 같은 글만 번역한다(공짜 번역기로 쓰는 것 방지).
     * 번역은 화면에만 표시되고 음성으로는 읽지 않는다.
     */
    @PostMapping("/translate")
    public ResponseEntity<?> translate(@Valid @RequestBody TranslateRequest request) {
        User user = currentUserProvider.requireCurrentUser();
        if (!chatRateLimiter.tryAcquire("translate:" + user.getId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."));
        }
        TranslationService.Outcome outcome = translationService.translate(user.getId(), request.text());
        if (outcome.error() != null) {
            return ResponseEntity.status(outcome.status()).body(Map.of("error", outcome.error()));
        }
        return ResponseEntity.ok(Map.of("translation", outcome.translation()));
    }

    /** 개념 카드에 수식·위키백과 그림을 붙이는 서비스(필드 주입). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.jarvisst.Lumi.service.ConceptEnricher conceptEnricher;

    private ResponseEntity<?> process(String rawMessage, String responseLanguage, ImageSanitizer.SanitizedImage image) {
        // 자원 남용 방어: 사용자당 분당 요청 수를 제한한다(Gemini 무료 할당량 보호).
        User user = currentUserProvider.requireCurrentUser();
        if (!chatRateLimiter.tryAcquire("user:" + user.getId())) {
            securityEvents.record(SecurityEvent.Type.CHAT_RATE_LIMIT, "대화 요청이 분당 한도를 넘음");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."));
        }

        // 탐지 → 대응: 프롬프트 인젝션 의심 문장은 기록하고, 반복되면 잠시 대화를 제한한다.
        // (한두 번은 경고 힌트만 붙여 처리하고, 3번 쌓이면 5분 제한 — 계정 잠금과 같은 방식)
        long restrictedLeft = strikeTracker.restrictedSecondsLeft(user.getId());
        if (restrictedLeft > 0) {
            securityEvents.record(SecurityEvent.Type.CHAT_RESTRICTED, "대화 제한 중 요청 (" + restrictedLeft + "초 남음)");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "비정상적인 요청이 반복되어 잠시 대화를 제한합니다. 약 "
                            + ((restrictedLeft + 59) / 60) + "분 뒤에 다시 시도해주세요."));
        }
        String injectionType = inputSanitizer.detectInjection(inputSanitizer.sanitizeUserText(rawMessage));
        if (injectionType != null) {
            securityEvents.record(SecurityEvent.Type.PROMPT_INJECTION, "프롬프트 인젝션 의심: " + injectionType);
            if (strikeTracker.recordStrike(user.getId())) {
                securityEvents.record(SecurityEvent.Type.CHAT_RESTRICTED, "인젝션 반복 탐지 — 대화 5분 제한");
            }
        }

        // 동시 처리 제한: 오래 걸리는 Gemini 호출을 여러 개 동시에 붙잡아 두는 자원 고갈 공격 방어.
        InFlightLimiter.Result slot = inFlightLimiter.tryEnter(user.getId());
        if (!slot.granted()) {
            securityEvents.record(SecurityEvent.Type.CONCURRENCY_LIMIT, "대화 동시 처리 한도 초과 (" + slot.denied() + ")");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", slot.denied() == InFlightLimiter.Denied.USER
                            ? "이전 요청을 처리하는 중입니다. 답변이 끝난 뒤 다시 시도해주세요."
                            : "지금 이용자가 많아 처리가 어렵습니다. 잠시 후 다시 시도해주세요."));
        }
        ChatResult result;
        List<ResultCard> cards;
        try (InFlightLimiter.Permit ignored = slot.permit()) {
            // 카드의 고정 문구는 사용자가 말한 언어를 따른다.
            ResultCardContext.begin(com.jarvisst.Lumi.service.CardLang.detect(rawMessage));
            try {
                result = geminiService.chat(rawMessage, responseLanguage, image);
                // 첫 인사 브리핑에서는 카드를 띄우지 않는다(사용자가 조회·검색했을 때만).
                cards = "(대화 시작)".equals(rawMessage.trim()) ? List.of() : ResultCardBuilder.withExplanation(ResultCardContext.drain(), rawMessage, result.reply());
            } finally {
                ResultCardContext.clear();
            }
        }

        // 개념 설명: 답변 끝의 [[수식: …]] 줄을 카드의 수식으로 옮기고(말풍선·음성·번역에는 나가지 않게 지운다),
        // 위키백과 그림·요약을 붙이고, 카드 문구를 사용자 언어에 맞춘다. 실패해도 원래 카드는 그대로 나간다.
        if (!"(대화 시작)".equals(rawMessage.trim())) {
            final String cardLang = com.jarvisst.Lumi.service.CardLang.detect(rawMessage);
            com.jarvisst.Lumi.service.ConceptEnricher.Extracted extracted =
                    com.jarvisst.Lumi.service.ConceptEnricher.extract(result.reply());
            if (extracted.reply() != null && !extracted.reply().equals(result.reply())) {
                result = new ChatResult(extracted.reply(), result.emotion(), result.links(), result.evidence(), result.toneLabel());
            }
            try {
                cards = conceptEnricher.build(cards, rawMessage, result.reply(), extracted.formulas(), cardLang);
            } catch (Exception ignored) {
                // 카드 마무리에 실패해도 대화는 그대로 나간다.
            }
        }

        // 교차 검증용으로 사용자별 마지막 질문/답변(+검색 근거)을 서버에 보관한다(인사 트리거는 제외).
        if (!"(대화 시작)".equals(rawMessage.trim())) {
            // 서버에 남는 직전 질문에도 주민번호·카드번호·비밀 키가 남지 않게 가린 값을 보관한다.
            lastExchangeStore.put(user.getId(),
                    sensitiveDataMasker.mask(rawMessage).text() + (image != null ? " [이미지 첨부]" : ""),
                    result.reply(), result.evidence());
        }

        ChatResponse.EmotionInfo emotionInfo = null;
        EmotionResult emotion = result.emotion();
        if (emotion != null) {
            emotionInfo = new ChatResponse.EmotionInfo(
                    EmotionSanitizer.label(emotion.primaryEmotion()),
                    EmotionSanitizer.label(emotion.secondaryEmotion()),
                    EmotionSanitizer.clamp(emotion.confidence(), 0.0, 1.0),
                    EmotionSanitizer.clamp(emotion.valence(), -1.0, 1.0),
                    EmotionSanitizer.clamp(emotion.arousal(), -1.0, 1.0),
                    result.toneLabel(),
                    emotion.ambiguous() && image == null // 사진을 이미 보냈다면 다시 청하지 않는다
            );
        }

        boolean translatable = translationService.remember(user.getId(), result.reply());
        return ResponseEntity.ok(new ChatResponse(result.reply(), emotionInfo, result.links(), translatable, cards));
    }
}
