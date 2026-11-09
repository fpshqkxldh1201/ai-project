package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.entity.SecurityEvent;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.security.InFlightLimiter;
import com.jarvisst.Lumi.security.SecurityEventService;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import com.jarvisst.Lumi.service.GeminiVerificationService;
import com.jarvisst.Lumi.service.LastExchangeStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lumi의 직전 답변을 네이버 검색 근거로 교차 검증한다(사용자가 요청할 때만 호출).
 * 검증 대상은 브라우저가 보낸 텍스트가 아니라, 서버가 이 사용자에게 마지막으로 돌려준
 * 질문/답변(+검색 근거)이다 — 요청 본문은 받지 않는다.
 */
@RestController
@RequestMapping("/api/cross-check")
public class CrossCheckController {

    private final GeminiVerificationService verificationService;
    private final CurrentUserProvider currentUserProvider;
    private final LastExchangeStore lastExchangeStore;
    private final InFlightLimiter inFlightLimiter;
    private final SecurityEventService securityEvents;

    public CrossCheckController(GeminiVerificationService verificationService,
                                CurrentUserProvider currentUserProvider,
                                LastExchangeStore lastExchangeStore,
                                InFlightLimiter inFlightLimiter, SecurityEventService securityEvents) {
        this.verificationService = verificationService;
        this.currentUserProvider = currentUserProvider;
        this.lastExchangeStore = lastExchangeStore;
        this.inFlightLimiter = inFlightLimiter;
        this.securityEvents = securityEvents;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> crossCheck(
            @RequestParam(name = "source", defaultValue = "both") String source) {
        // 요청 본문은 받지 않는다. 선택값(source)만 허용 목록으로 검사해서 쓴다.
        if (!GeminiVerificationService.isValidSource(source)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "검증 근거는 naver, google, both 중 하나여야 합니다."));
        }
        User user = currentUserProvider.requireCurrentUser();
        LastExchangeStore.Exchange exchange = lastExchangeStore.get(user.getId());
        if (exchange == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "검증할 직전 답변이 없습니다. 먼저 Lumi에게 질문해 주세요."));
        }

        // 동시 처리 제한(검색 + Gemini 호출을 여러 개 붙잡아 두는 자원 고갈 공격 방어)
        InFlightLimiter.Result slot = inFlightLimiter.tryEnter(user.getId());
        if (!slot.granted()) {
            securityEvents.record(SecurityEvent.Type.CONCURRENCY_LIMIT, "교차 검증 동시 처리 한도 초과 (" + slot.denied() + ")");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "이전 요청을 처리하는 중입니다. 잠시 후 다시 시도해주세요."));
        }
        GeminiVerificationService.Outcome outcome;
        try (InFlightLimiter.Permit ignored = slot.permit()) {
            outcome = verificationService.verify(
                    user.getId(), exchange.question(), exchange.answer(), exchange.evidence(), source);
        }

        if (outcome.error() != null) {
            return ResponseEntity.status(outcome.status()).body(Map.of("error", outcome.error()));
        }
        return ResponseEntity.ok(Map.of(
                "verdict", outcome.verdict(),
                "detail", outcome.detail(),
                "model", outcome.model()));
    }
}
