package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.service.CardLang;
import com.jarvisst.Lumi.service.ScreenAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 화면 공유 분석 API. 로그인한 사용자만(SecurityConfig의 anyRequest().authenticated()).
 *  POST /api/vision/screen {image, question, lang} → {card} 또는 {error}
 * 그림 분석은 비싸므로 사용자당 10분에 8번, 서버 전체 동시 2개까지.
 */
@RestController
@RequestMapping("/api/vision")
public class ScreenAnalysisController {

    private static final int PER_WINDOW = 8;
    private static final long WINDOW_MS = 10 * 60_000L;

    private final ScreenAnalysisService service;
    private final Semaphore gate = new Semaphore(2);
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public ScreenAnalysisController(ScreenAnalysisService service) {
        this.service = service;
    }

    public record ScreenRequest(String image, String question, String lang) {
    }

    @PostMapping("/screen")
    public ResponseEntity<?> screen(@RequestBody ScreenRequest req, Principal principal) {
        if (req == null || req.image() == null || req.image().length() > ScreenAnalysisService.MAX_B64 + 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "이미지가 올바르지 않아요."));
        }
        String who = principal == null ? "anonymous" : principal.getName();
        if (!allow(who)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "화면 분석은 잠시 후에 다시 할 수 있어요."));
        }
        boolean got = false;
        try {
            got = gate.tryAcquire(2, TimeUnit.SECONDS);
            if (!got) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "지금은 다른 분석이 진행 중이에요."));
            }
            String q = req.question() == null ? "" : req.question();
            String lang = "ko".equals(req.lang()) || "en".equals(req.lang()) ? req.lang()
                    : (q.isBlank() ? "ko" : CardLang.detect(q));
            ScreenAnalysisService.Result r = service.analyze(req.image(), q, lang);
            if (r.card() == null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", r.error()));
            }
            return ResponseEntity.ok(Map.of("card", r.card()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "중단됐어요."));
        } finally {
            if (got) {
                gate.release();
            }
        }
    }

    private boolean allow(String who) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= PER_WINDOW) {
                return false;
            }
            q.addLast(now);
        }
        if (recent.size() > 2000) {
            recent.clear();
        }
        return true;
    }
}
