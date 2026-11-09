package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.notification.ProactiveNotificationResponse;
import com.jarvisst.Lumi.entity.ProactiveNotification;
import com.jarvisst.Lumi.service.ProactiveNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 프런트엔드가 주기적으로(폴링) 호출해서, Lumi가 스스로 말을 걸어야 할 내용이 쌓여 있는지
 * 확인하는 엔드포인트. 메시지 입력창에 커서가 있는지, 지금 화면을 보고 있는지와 전혀 무관하게
 * 항상 동작한다 — 리마인더/일정 임박을 ProactiveNotificationScheduler가 여기에 미리 쌓아둔다.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final ProactiveNotificationService notificationService;

    public NotificationController(ProactiveNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/pending")
    public Map<String, List<ProactiveNotificationResponse>> getPending() {
        List<ProactiveNotificationResponse> notifications = notificationService.fetchPendingAndMarkDelivered()
                .stream()
                .map(ProactiveNotificationResponse::from)
                .toList();
        return Map.of("notifications", notifications);
    }
}
