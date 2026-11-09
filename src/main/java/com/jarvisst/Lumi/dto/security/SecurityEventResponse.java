package com.jarvisst.Lumi.dto.security;

import com.jarvisst.Lumi.entity.SecurityEvent;

import java.time.LocalDateTime;

/** 본인의 보안 이벤트 한 줄(GET /api/security/events) — 다른 사용자의 이벤트는 섞이지 않는다. */
public record SecurityEventResponse(String eventType, String detail, String ipAddress, LocalDateTime occurredAt) {
    public static SecurityEventResponse from(SecurityEvent e) {
        return new SecurityEventResponse(e.getEventType().name(), e.getDetail(), e.getIpAddress(), e.getOccurredAt());
    }
}
