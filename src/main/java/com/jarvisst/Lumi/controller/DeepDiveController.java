package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.service.CardLang;
import com.jarvisst.Lumi.service.DeepDivePlanner;
import com.jarvisst.Lumi.service.DeepDiveService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 꼬리 탐색 API. 로그인한 사용자만 부를 수 있다(SecurityConfig의 anyRequest().authenticated()).
 *  POST /api/deepdive/plan  — 이 대화를 더 파고들지, 몇 단계인지(규칙만 써서 비용 없음)
 *  POST /api/deepdive/step  — 한 단계 실행 → 카드 한 장 + 다음 단계용 ctx
 *
 * 남용 방지: 사용자당 10분에 40단계, 서버 전체 동시 4개까지. 입력 길이·ctx 크기는 모두 제한한다.
 */
@RestController
@RequestMapping("/api/deepdive")
public class DeepDiveController {

    private static final int MAX_MESSAGE = 300;
    private static final int MAX_REPLY = 4000;
    private static final int STEPS_PER_WINDOW = 40;
    private static final long WINDOW_MS = 10 * 60_000L;

    private final DeepDiveService service;
    private final Semaphore gate = new Semaphore(4);
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public DeepDiveController(DeepDiveService service) {
        this.service = service;
    }

    public record PlanRequest(String message, String reply, List<String> cardTypes) {
    }

    public record StepRequest(String kind, Integer step, String message, String reply, Map<String, String> ctx) {
    }

    @PostMapping("/plan")
    public ResponseEntity<?> plan(@RequestBody PlanRequest req) {
        if (req == null || req.message() == null || req.message().length() > MAX_MESSAGE * 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "message가 올바르지 않습니다."));
        }
        List<String> types = req.cardTypes() == null ? List.of() : req.cardTypes().stream().filter(t -> t != null && t.length() < 20).limit(10).toList();
        DeepDivePlanner.Plan plan = DeepDivePlanner.plan(req.message(), clip(req.reply(), MAX_REPLY), types);
        List<Map<String, String>> steps = new ArrayList<>();
        for (DeepDivePlanner.Step s : plan.steps()) {
            steps.add(Map.of("id", s.id(), "label", s.label()));
        }
        return ResponseEntity.ok(Map.of("kind", plan.kindName(), "steps", steps));
    }

    @PostMapping("/step")
    public ResponseEntity<?> step(@RequestBody StepRequest req, Principal principal) {
        if (req == null || req.kind() == null || req.step() == null || req.message() == null
                || req.message().length() > MAX_MESSAGE * 2 || req.step() < 0 || req.step() > 3) {
            return ResponseEntity.badRequest().body(Map.of("error", "요청이 올바르지 않습니다."));
        }
        DeepDivePlanner.Kind kind;
        try {
            kind = DeepDivePlanner.Kind.valueOf(req.kind().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind가 올바르지 않습니다."));
        }
        String who = principal == null ? "anonymous" : principal.getName();
        if (!allow(who)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "잠시 후 다시 시도해주세요."));
        }
        boolean got = false;
        try {
            got = gate.tryAcquire(2, TimeUnit.SECONDS);
            if (!got) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "지금은 다른 탐색이 진행 중이에요."));
            }
            // 이 대화가 정말 탐색 대상인지 서버가 다시 확인한다(화면이 보낸 kind를 그대로 믿지 않는다).
            DeepDivePlanner.Plan plan = DeepDivePlanner.plan(req.message(), clip(req.reply(), MAX_REPLY), List.of("explain"));
            if (plan.kind() != kind || req.step() >= plan.steps().size()) {
                return ResponseEntity.ok(Map.of("done", true));
            }
            String lang = CardLang.detect(req.message());
            DeepDiveService.StepResult r = service.step(kind, req.step(), req.message(), clip(req.reply(), MAX_REPLY), lang, cleanCtx(req.ctx()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("card", r.card());
            body.put("ctx", r.ctx());
            body.put("done", r.done() || req.step() >= plan.steps().size() - 1);
            return ResponseEntity.ok(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "중단됐습니다."));
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
            if (q.size() >= STEPS_PER_WINDOW) {
                return false;
            }
            q.addLast(now);
        }
        if (recent.size() > 2000) {
            recent.clear();
        }
        return true;
    }

    private static Map<String, String> cleanCtx(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            for (Map.Entry<String, String> e : in.entrySet()) {
                if (out.size() < 10 && e.getKey() != null && e.getValue() != null
                        && e.getKey().matches("[a-z]{1,12}") && e.getValue().length() <= 120) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }
}
