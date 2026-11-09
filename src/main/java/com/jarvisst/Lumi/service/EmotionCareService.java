package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.User;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 선제 알림 트리거(감정 기반): 부정적인 기분이 연속으로 이어지는 흐름(NEGATIVE_STREAK)이 보이면 Lumi가 먼저
 * 짧게 안부를 건넨다. 알림은 기존 ProactiveNotificationService 큐로 들어가 프런트엔드가 폴링해 음성으로 안내한다.
 *
 * 남발을 막는 장치: 같은 사용자에게 3시간 이내 재알림 금지, 24시간에 최대 2회.
 * 사용자가 설정에서 "먼저 말 걸기"를 끄면 아무것도 보내지 않는다. 호출 기록은 메모리에만 두고(재시작하면 초기화),
 * 문구는 고정 템플릿이라 감정 분석 결과나 대화 내용이 알림에 섞이지 않는다.
 */
@Component
public class EmotionCareService {

    static final long COOLDOWN_MILLIS = 3L * 60 * 60 * 1000;
    static final long WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    static final int MAX_PER_WINDOW = 2;
    private static final int MAX_USERS = 2000;
    /** 이 값 이하(매우 부정적)면 주변이나 전문 상담에 도움을 구해보라는 안내를 덧붙인다. */
    static final double VERY_NEGATIVE = -0.7;

    private final ProactiveNotificationService notificationService;
    private final Map<Long, Deque<Long>> sent = new ConcurrentHashMap<>();

    public EmotionCareService(ProactiveNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 알림을 큐에 넣었으면 true. 조건이 안 맞으면 조용히 false. */
    public boolean maybeCare(User user, EmotionTrendStore.Trend trend, double valence) {
        if (user == null || trend != EmotionTrendStore.Trend.NEGATIVE_STREAK || !user.isProactiveCareEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!allow(user.getId(), now)) {
            return false;
        }
        NotificationText.Message m = NotificationText.care(NotificationText.languageOf(user), valence <= VERY_NEGATIVE);
        notificationService.queue(user, m.text(), m.translation());
        return true;
    }

    private boolean allow(Long userId, long now) {
        if (userId == null) {
            return false;
        }
        if (sent.size() >= MAX_USERS && !sent.containsKey(userId)) {
            sent.values().removeIf(d -> d.isEmpty() || now - d.peekLast() > WINDOW_MILLIS);
            if (sent.size() >= MAX_USERS) {
                return false;
            }
        }
        boolean[] ok = {false};
        sent.compute(userId, (k, old) -> {
            Deque<Long> d = old == null ? new ArrayDeque<>() : old;
            while (!d.isEmpty() && now - d.peekFirst() > WINDOW_MILLIS) {
                d.pollFirst();
            }
            boolean cooledDown = d.isEmpty() || now - d.peekLast() >= COOLDOWN_MILLIS;
            if (cooledDown && d.size() < MAX_PER_WINDOW) {
                d.addLast(now);
                ok[0] = true;
            }
            return d;
        });
        return ok[0];
    }
}
