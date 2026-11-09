package com.jarvisst.Lumi.dto.auth;

import com.jarvisst.Lumi.entity.LoginAuditLog;

import java.time.LocalDateTime;

/** 본인의 로그인 이력 한 줄(GET /api/auth/audit) — 다른 사용자의 로그는 절대 섞이지 않는다. */
public record AuditLogEntryResponse(
        String eventType,
        String provider,
        String ipAddress,
        LocalDateTime occurredAt
) {
    public static AuditLogEntryResponse from(LoginAuditLog log) {
        return new AuditLogEntryResponse(
                log.getEventType().name(), log.getProvider(), log.getIpAddress(), log.getOccurredAt());
    }
}
