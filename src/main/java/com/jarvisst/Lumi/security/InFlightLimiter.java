package com.jarvisst.Lumi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시 처리 제한 — 자원 고갈형 DoS 대응 (정보보안개론 3장: HTTP GET 플러딩, 동적 요청 플러딩).
 *
 * 요청 '횟수' 제한(ChatRateLimiter, ApiRequestGuardFilter)만으로는, 한 번에 오래 걸리는 요청(Gemini·검색 호출은
 * 수 초~수십 초)을 동시에 여러 개 보내 서버 스레드와 외부 API 연결을 붙잡아 두는 공격을 막기 어렵다.
 * 그래서 Gemini를 부르는 엔드포인트(대화, 교차 검증)에 '지금 처리 중인 요청 수' 상한을 둔다.
 *  - 사용자당 security.concurrency.per-user (기본 2)
 *  - 서버 전체  security.concurrency.global-max (기본 12)
 * 자리가 없으면 대기시키지 않고 바로 거절(429)해서 대기열이 쌓이는 것도 막는다.
 */
@Component
public class InFlightLimiter {

    public enum Denied { USER, GLOBAL }

    /** try-with-resources로 쓰면 처리가 끝날 때(예외 포함) 자리가 반납된다. */
    public static final class Permit implements AutoCloseable {
        private final InFlightLimiter owner;
        private final Long userId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(InFlightLimiter owner, Long userId) {
            this.owner = owner;
            this.userId = userId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(userId);
            }
        }
    }

    /** 결과: permit이 있으면 통과, 없으면 denied에 거절 사유. */
    public record Result(Permit permit, Denied denied) {
        public boolean granted() {
            return permit != null;
        }
    }

    private final int perUser;
    private final int globalMax;
    private final AtomicInteger global = new AtomicInteger();
    private final Map<Long, AtomicInteger> perUserCounts = new ConcurrentHashMap<>();

    public InFlightLimiter(@Value("${security.concurrency.per-user:2}") int perUser,
                           @Value("${security.concurrency.global-max:12}") int globalMax) {
        this.perUser = perUser;
        this.globalMax = globalMax;
    }

    public Result tryEnter(Long userId) {
        if (global.incrementAndGet() > globalMax) {
            global.decrementAndGet();
            return new Result(null, Denied.GLOBAL);
        }
        AtomicInteger mine = perUserCounts.computeIfAbsent(userId, k -> new AtomicInteger());
        if (mine.incrementAndGet() > perUser) {
            mine.decrementAndGet();
            global.decrementAndGet();
            return new Result(null, Denied.USER);
        }
        return new Result(new Permit(this, userId), null);
    }

    private void release(Long userId) {
        global.decrementAndGet();
        AtomicInteger mine = perUserCounts.get(userId);
        if (mine != null && mine.decrementAndGet() <= 0) {
            perUserCounts.remove(userId, mine);
        }
    }

    public int currentGlobal() {
        return global.get();
    }
}
