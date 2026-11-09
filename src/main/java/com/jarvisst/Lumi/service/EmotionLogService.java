package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.EmotionLog;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.EmotionLogRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 감정 시계열 로그: 대화마다 읽힌 감정 "수치"를 사용자별로 쌓고, 날짜별로 요약해서 돌려준다.
 * - 대화 내용·사진은 저장하지 않는다(감정 분류와 수치만).
 * - 사용자가 설정에서 끌 수 있고(emotionLogEnabled), 전부 삭제할 수 있고, 보관 기간이 지나면 자동 삭제된다.
 * - 확신도가 낮아 톤 반영을 하지 않은(NONE) 분석은 기록하지 않는다(노이즈 방지).
 */
@Service
public class EmotionLogService {

    public static final int MAX_DAYS = 31;

    /** 하루 요약 한 칸. avgValence는 -1(부정) ~ +1(긍정), 기록이 없는 날은 count=0. */
    public record DayStat(String date, String label, int count, Double avgValence, Double avgArousal, String topMood) {
    }

    private final EmotionLogRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final int retentionDays;

    public EmotionLogService(EmotionLogRepository repository, CurrentUserProvider currentUserProvider,
                              @Value("${emotion.log.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** 사용자가 기록을 껐으면 아무것도 저장하지 않는다. */
    public void record(User owner, EmotionTonePolicy.Reading reading, String source) {
        if (owner == null || reading == null || reading.level() == EmotionTonePolicy.Level.NONE
                || !owner.isEmotionLogEnabled() || reading.mood() == null) {
            return;
        }
        repository.save(new EmotionLog(owner, reading.mood().name(), clamp(reading.valence()),
                clamp(reading.arousal()), Math.max(0, Math.min(1, reading.confidence())), source));
    }

    /** 로그인한 사용자 본인의 최근 days일(오늘 포함) 요약. 기록 없는 날도 count=0으로 채워서 돌려준다. */
    @Transactional(readOnly = true)
    public List<DayStat> dailySummary(int days) {
        return dailySummary(currentUserProvider.requireCurrentUser(), days);
    }

    @Transactional(readOnly = true)
    public List<DayStat> dailySummary(User owner, int days) {
        int n = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(n - 1L);
        List<EmotionLog> logs = repository.findByOwnerAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                owner, first.atStartOfDay());

        Map<LocalDate, List<EmotionLog>> byDay = new HashMap<>();
        for (EmotionLog log : logs) {
            byDay.computeIfAbsent(log.getCreatedAt().toLocalDate(), k -> new ArrayList<>()).add(log);
        }
        List<DayStat> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate day = first.plusDays(i);
            List<EmotionLog> list = byDay.getOrDefault(day, List.of());
            String label = day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
            if (list.isEmpty()) {
                result.add(new DayStat(day.toString(), label, 0, null, null, null));
                continue;
            }
            double v = list.stream().mapToDouble(EmotionLog::getValence).average().orElse(0);
            double a = list.stream().mapToDouble(EmotionLog::getArousal).average().orElse(0);
            Map<String, Integer> freq = new HashMap<>();
            list.forEach(l -> freq.merge(l.getMood(), 1, Integer::sum));
            String top = freq.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            result.add(new DayStat(day.toString(), label, list.size(), round(v), round(a), top));
        }
        return result;
    }

    /**
     * 대화를 새로 시작할 때 모델에 줄 한 줄 힌트: 어제 기분이 많이 가라앉아 있었으면 안부를 부드럽게 물어보게 한다.
     * 기록이 적거나(3건 미만) 평소 수준이면 null — 억지로 언급하지 않는다.
     */
    public String yesterdayHint(User owner) {
        if (owner == null || !owner.isEmotionLogEnabled()) {
            return null;
        }
        try {
            List<DayStat> stats = dailySummary(owner, 2);
            DayStat yesterday = stats.get(0);
            if (yesterday.count() < 3 || yesterday.avgValence() == null || yesterday.avgValence() > -0.3) {
                return null;
            }
            return "[내부 안내 — 참고만 하고 이 안내 자체를 언급하지 마세요] 어제 대화 전반의 기분이 많이 가라앉아 있었습니다. "
                    + "인사 끝에 어제보다 나아졌는지 부담 없이 한 줄로 안부를 묻되, 감정을 단정하거나 분석 결과를 말하지 마세요.";
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public int deleteAllMine() {
        return repository.deleteAllByOwner(currentUserProvider.requireCurrentUser());
    }

    @Transactional
    public int purgeExpired() {
        return repository.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
    }

    private static double clamp(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        return Math.max(-1, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
