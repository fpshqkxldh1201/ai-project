package com.jarvisst.Lumi.scheduler;

import com.jarvisst.Lumi.entity.Reminder;
import com.jarvisst.Lumi.entity.Schedule;
import com.jarvisst.Lumi.repository.ReminderRepository;
import com.jarvisst.Lumi.repository.ScheduleRepository;
import com.jarvisst.Lumi.service.NotificationText;
import com.jarvisst.Lumi.service.ProactiveNotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 1분마다 "임박한" 리마인더/일정을 확인해서, Lumi가 사용자 입력 없이 먼저 말을 걸 내용을
 * ProactiveNotificationService에 쌓아둔다. 화면에 메시지 입력창 포커스가 있는지와 전혀
 * 무관하게 항상 동작한다 — 프런트엔드는 그냥 /api/notifications/pending을 주기적으로
 * 확인하기만 하면 된다.
 */
@Component
public class ProactiveNotificationScheduler {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final ReminderRepository reminderRepository;
    private final ScheduleRepository scheduleRepository;
    private final ProactiveNotificationService notificationService;

    public ProactiveNotificationScheduler(ReminderRepository reminderRepository,
                                           ScheduleRepository scheduleRepository,
                                           ProactiveNotificationService notificationService) {
        this.reminderRepository = reminderRepository;
        this.scheduleRepository = scheduleRepository;
        this.notificationService = notificationService;
    }

    /** 알림 시각이 지난(최근 1시간 이내로 한정 — 서버가 꺼져 있던 동안 쌓인 걸 몰아서 알리지 않도록) 리마인더. */
    @Scheduled(fixedRate = 60_000)
    public void checkDueReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Reminder> due = reminderRepository.findDueForProactiveNotification(now.minusHours(1), now);
        for (Reminder reminder : due) {
            // 사용자가 말하는 언어로 알리고, 외국어면 한국어 번역을 화면용으로 함께 쌓는다(owner는 JOIN FETCH로 이미 로딩됨).
            NotificationText.Message m = NotificationText.reminder(
                    NotificationText.languageOf(reminder.getOwner()), reminder.getContent());
            notificationService.queue(reminder.getOwner(), m.text(), m.translation());
            reminder.markNotified();
        }
        if (!due.isEmpty()) {
            reminderRepository.saveAll(due);
        }
    }

    /** 10분 안에 시작하는 일정. */
    @Scheduled(fixedRate = 60_000)
    public void checkUpcomingSchedules() {
        LocalDateTime now = LocalDateTime.now();
        List<Schedule> upcoming = scheduleRepository.findDueForProactiveNotification(now, now.plusMinutes(10));
        for (Schedule schedule : upcoming) {
            String time = schedule.getStartTime().format(TIME_FORMAT);
            NotificationText.Message m = NotificationText.upcomingSchedule(
                    NotificationText.languageOf(schedule.getOwner()), time, schedule.getTitle());
            notificationService.queue(schedule.getOwner(), m.text(), m.translation());
            schedule.markNotified();
        }
        if (!upcoming.isEmpty()) {
            scheduleRepository.saveAll(upcoming);
        }
    }
}
