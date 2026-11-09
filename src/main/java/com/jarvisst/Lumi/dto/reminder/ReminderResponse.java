package com.jarvisst.Lumi.dto.reminder;

import com.jarvisst.Lumi.entity.Reminder;

/** 화면 UPCOMING REMINDERS 패널용 — 알림 시각(remindAt)은 일정과 같은 ISO 로컬 문자열. */
public record ReminderResponse(Long id, String content, String remindAt) {
    public static ReminderResponse from(Reminder r) {
        return new ReminderResponse(r.getId(), r.getContent(), r.getRemindAt().toString());
    }
}
