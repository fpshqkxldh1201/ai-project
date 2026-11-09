package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자당 대화 요청 속도 제한(자원 남용 방어). 로그인한 계정이 스크립트로 /api/chat을
 * 연타해 Gemini 무료 할당량을 한꺼번에 소진시키거나 서버를 붙잡는 것을 막는다.
 * 1분 슬라이딩 윈도우에 사용자당 MAX_PER_WINDOW회까지 허용한다(정상 대화·음성 사용엔 충분한 값).
 */
@Component
public class ChatRateLimiter {

    private static final int MAX_PER_WINDOW = 20;
    private static final long WINDOW_MS = 60_000L;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            if (deque.size() >= MAX_PER_WINDOW) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
