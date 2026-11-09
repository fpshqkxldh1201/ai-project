package com.jarvisst.Lumi.scheduler;

import com.jarvisst.Lumi.service.EmotionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 보관 기간(emotion.log.retention-days, 기본 90일)이 지난 감정 기록을 매일 새벽에 삭제한다(개인정보 최소 보관). */
@Component
public class EmotionLogRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmotionLogRetentionScheduler.class);

    private final EmotionLogService emotionLogService;

    public EmotionLogRetentionScheduler(EmotionLogService emotionLogService) {
        this.emotionLogService = emotionLogService;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void purge() {
        try {
            int deleted = emotionLogService.purgeExpired();
            if (deleted > 0) {
                log.info("[감정 기록] 보관 기간이 지난 {}건 삭제", deleted);
            }
        } catch (Exception e) {
            log.warn("[감정 기록] 만료 삭제 실패: {}", e.toString());
        }
    }
}
