package com.jarvisst.Lumi.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자별 "최근 감정 흐름"을 서버 메모리에 잠깐 보관한다(최근 6턴, 30분). 한 번의 분석이 아니라
 * 흐름을 봐야 "부정적인 기분이 계속 이어지는지", "나아지는 중인지"를 알 수 있기 때문이다.
 * DB에 저장하지 않으며 서버를 재시작하면 사라진다(감정은 민감한 정보라 최소한만 보관).
 */
@Component
public class EmotionTrendStore {

    public enum Trend { STEADY, NEGATIVE_STREAK, RECOVERING }

    private record Point(long at, double valence, double arousal) {
    }

    private static final long TTL_MILLIS = 30L * 60 * 1000;
    private static final int MAX_POINTS = 6;
    private static final int MAX_USERS = 1000;
    static final double NEGATIVE_VALENCE = -0.2;

    private final Map<Long, List<Point>> store = new ConcurrentHashMap<>();

    /** 이번 턴의 감정을 기록하고, 기록을 포함한 흐름을 판정해 돌려준다. */
    public Trend recordAndAnalyze(Long userId, double valence, double arousal) {
        if (userId == null) {
            return Trend.STEADY;
        }
        long now = System.currentTimeMillis();
        if (store.size() >= MAX_USERS && !store.containsKey(userId)) {
            store.values().removeIf(l -> l.isEmpty() || now - l.get(l.size() - 1).at() > TTL_MILLIS);
            if (store.size() >= MAX_USERS) {
                return Trend.STEADY;
            }
        }
        List<Point> snapshot = store.compute(userId, (k, old) -> {
            List<Point> list = old == null ? new ArrayList<>() : new ArrayList<>(old);
            list.removeIf(p -> now - p.at() > TTL_MILLIS);
            list.add(new Point(now, valence, arousal));
            while (list.size() > MAX_POINTS) {
                list.remove(0);
            }
            return list;
        });
        return analyze(snapshot);
    }

    private static Trend analyze(List<Point> pts) {
        int n = pts.size();
        int streak = 0;
        for (int i = n - 1; i >= 0 && pts.get(i).valence() <= NEGATIVE_VALENCE; i--) {
            streak++;
        }
        if (streak >= 3) {
            return Trend.NEGATIVE_STREAK;
        }
        if (n >= 3 && pts.get(n - 2).valence() <= NEGATIVE_VALENCE && pts.get(n - 3).valence() <= NEGATIVE_VALENCE
                && pts.get(n - 1).valence() >= 0.1) {
            return Trend.RECOVERING;
        }
        return Trend.STEADY;
    }

    /** 로그아웃할 때 지운다. */
    public void clear(Long userId) {
        if (userId != null) {
            store.remove(userId);
        }
    }
}
