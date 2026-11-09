package com.jarvisst.Lumi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 정보보안개론 2주차 "계정 관리(무차별 대입 공격 방어)" 보완.
 *
 * User.registerFailedLogin()이 하는 계정 단위 잠금(한 아이디에 5번 연속 실패 시 잠금)은,
 * 공격자가 "한 아이디에 5번"이 아니라 "서로 다른 아이디에 각 1~2번씩" 시도하는 분산
 * 크리덴셜 스터핑(credential stuffing)에는 전혀 걸리지 않는다 — 어느 계정도 5회
 * 임계값을 넘지 않기 때문이다.
 *
 * 이 클래스는 아이디와 무관하게 "같은 IP가 짧은 시간 동안 얼마나 많은 로그인 실패를
 * 만들어내는지"만 본다. 계정 잠금과는 독립적인 두 번째 방어선이다.
 *
 * 단일 인스턴스 배포를 전제로 메모리에만 기록한다(서버 재시작하면 리셋됨). 여러
 * 인스턴스로 수평 확장하게 되면 Redis 등 공유 저장소로 옮겨야 한다.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final int MAX_FAILURES_PER_IP = 20;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failuresByIp = new ConcurrentHashMap<>();
    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public boolean isBlocked(String ip) {
        if (ip == null) {
            return false;
        }
        Instant until = blockedUntil.get(ip);
        if (until == null) {
            return false;
        }
        if (until.isAfter(Instant.now())) {
            return true;
        }
        blockedUntil.remove(ip);
        return false;
    }

    public void recordFailure(String ip) {
        if (ip == null) {
            return;
        }
        Instant now = Instant.now();
        Deque<Instant> attempts = failuresByIp.computeIfAbsent(ip, key -> new ConcurrentLinkedDeque<>());
        attempts.addLast(now);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(now.minus(WINDOW))) {
            attempts.pollFirst();
        }
        if (attempts.size() >= MAX_FAILURES_PER_IP) {
            blockedUntil.put(ip, now.plus(BLOCK_DURATION));
            log.warn("[로그인 속도 제한] IP 차단: {} ({}분간, 최근 {}분 내 로그인 실패 {}회)",
                    ip, BLOCK_DURATION.toMinutes(), WINDOW.toMinutes(), attempts.size());
        }
    }

    public void recordSuccess(String ip) {
        if (ip == null) {
            return;
        }
        failuresByIp.remove(ip);
        blockedUntil.remove(ip);
    }
}
