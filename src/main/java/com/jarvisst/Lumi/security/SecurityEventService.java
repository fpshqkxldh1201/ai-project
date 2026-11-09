package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.SecurityEvent;
import com.jarvisst.Lumi.repository.SecurityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 보안 이벤트를 DB와 서버 로그에 남긴다(탐지 → 기록).
 *
 * 공격이 한창일 때 같은 종류의 이벤트가 쏟아지면 기록 자체가 DB를 채우는 또 다른 공격 경로가 되므로,
 * 같은 (이벤트 종류 + 사용자 또는 IP)는 DEDUPE_MS 안에 한 번만 저장한다.
 * 기록 실패가 본 기능(대화 등)을 막으면 안 되므로 모든 예외는 삼킨다.
 */
@Service
public class SecurityEventService {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventService.class);
    private static final long DEDUPE_MS = 30_000L;
    private static final int MAX_TRACKED_KEYS = 5_000;

    private final SecurityEventRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final ClientIpResolver clientIpResolver;
    private final InputSanitizer inputSanitizer;

    private final Map<String, Long> lastRecordedAt = new ConcurrentHashMap<>();

    public SecurityEventService(SecurityEventRepository repository, CurrentUserProvider currentUserProvider,
                                ClientIpResolver clientIpResolver, InputSanitizer inputSanitizer) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.clientIpResolver = clientIpResolver;
        this.inputSanitizer = inputSanitizer;
    }

    /** 요청 처리 스레드 안에서 호출: 현재 로그인 사용자와 요청 IP를 알아서 채운다. */
    public void record(SecurityEvent.Type type, String detail) {
        Long userId = null;
        String ip = null;
        try {
            userId = currentUserProvider.requireCurrentUser().getId();
        } catch (Exception ignored) {
            // 비로그인 상태면 사용자 없이 기록
        }
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                ip = clientIpResolver.resolve(attrs.getRequest());
            }
        } catch (Exception ignored) {
            // IP를 못 구해도 기록은 남긴다
        }
        record(userId, type, detail, ip);
    }

    public void record(Long userId, SecurityEvent.Type type, String detail, String ip) {
        try {
            String subject = userId != null ? "u" + userId : "ip" + ip;
            String key = type + ":" + subject;
            long now = System.currentTimeMillis();
            Long previous = lastRecordedAt.get(key);
            if (previous != null && now - previous < DEDUPE_MS) {
                return;
            }
            if (lastRecordedAt.size() > MAX_TRACKED_KEYS) {
                lastRecordedAt.clear();
            }
            lastRecordedAt.put(key, now);

            String cleanDetail = inputSanitizer.cleanForStorage(detail, 250);
            log.warn("[보안 이벤트] {} user={} ip={} — {}", type, userId, ip, cleanDetail);
            repository.save(new SecurityEvent(userId, type, cleanDetail, ip));
        } catch (Exception e) {
            log.warn("[보안 이벤트] 기록 실패 — {}", e.toString());
        }
    }
}
