package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.reminder.ReminderResponse;
import com.jarvisst.Lumi.service.ReminderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 화면(UPCOMING REMINDERS 패널·요약 카드)용 조회 엔드포인트. 로그인한 본인의, 아직 완료되지 않았고
 * 알림 시각이 남은 리마인더만 시각순으로 돌려준다(ReminderService가 소유자로 걸러냄).
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    /** 화면에 한 번에 보낼 최대 개수(패널은 몇 개만 보여주고, 카드는 개수만 쓴다). */
    private static final int MAX_ITEMS = 20;

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping
    public Map<String, List<ReminderResponse>> getUpcomingReminders() {
        List<ReminderResponse> reminders = reminderService.getUpcoming().stream()
                .limit(MAX_ITEMS)
                .map(ReminderResponse::from)
                .toList();
        return Map.of("reminders", reminders);
    }
}
