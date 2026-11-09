package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프롬프트 인젝션을 반복하는 계정에 대한 자동 대응(탐지 → 대응).
 * 10분 안에 인젝션 의심 메시지가 3번 탐지되면 그 계정의 대화를 5분간 제한한다.
 * (로그인 5회 실패 시 계정을 잠그는 것과 같은 발상 — 한두 번은 정상 사용자의 우연한 표현일 수 있어 경고만 하고,
 * 반복될 때만 막는다.)
 */
@Component
public class InjectionStrikeTracker {

    private static final int STRIKES_TO_RESTRICT = 3;
    private static final long STRIKE_WINDOW_MS = 10 * 60_000L;
    private static final long RESTRICT_MS = 5 * 60_000L;
    private static final int MAX_TRACKED_USERS = 10_000;

    private final Map<Long, Deque<Long>> strikes = new ConcurrentHashMap<>();
    private final Map<Long, Long> restrictedUntil = new ConcurrentHashMap<>();

    /** 제한 중이면 남은 초를, 아니면 0을 돌려준다. */
    public long restrictedSecondsLeft(Long userId) {
        Long until = restrictedUntil.get(userId);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            restrictedUntil.remove(userId);
            return 0;
        }
        return (left + 999) / 1000;
    }

    /** 인젝션 의심 1회를 기록한다. 이번 기록으로 제한이 걸렸으면 true. */
    public boolean recordStrike(Long userId) {
        if (strikes.size() > MAX_TRACKED_USERS) {
            strikes.clear();
        }
        long now = System.currentTimeMillis();
        Deque<Long> deque = strikes.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > STRIKE_WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            if (deque.size() >= STRIKES_TO_RESTRICT) {
                deque.clear();
                restrictedUntil.put(userId, now + RESTRICT_MS);
                return true;
            }
            return false;
        }
    }
}
