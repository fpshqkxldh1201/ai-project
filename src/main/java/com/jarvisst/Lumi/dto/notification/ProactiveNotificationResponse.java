package com.jarvisst.Lumi.dto.notification;

import com.jarvisst.Lumi.entity.ProactiveNotification;

public record ProactiveNotificationResponse(
        Long id,
        String message,
        String createdAt,
        String translation
) {
    public static ProactiveNotificationResponse from(ProactiveNotification notification) {
        return new ProactiveNotificationResponse(
                notification.getId(),
                notification.getMessage(),
                notification.getCreatedAt().toString(),
                notification.getTranslation()
        );
    }
}
