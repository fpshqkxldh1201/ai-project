package com.jarvisst.Lumi.dto.security;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 화면의 SECURITY STATUS 패널용(GET /api/security/status). 본인 계정의 최근 24시간 이벤트와
 * 서버가 지금 켜 둔 방어 장치만 담는다 — 다른 사용자·IP·내부 설정 값(키 등)은 들어가지 않는다.
 */
public record SecurityStatusResponse(
        String level,                 // SECURE / NOTICE / ALERT
        String headline,              // 한 줄 요약
        int eventsLast24h,
        List<Item> categories,        // 24시간 동안 종류별 차단·탐지 횟수(0 제외)
        List<Protection> protections, // 방어 장치 on/off
        List<Recent> recent,          // 최근 5건
        int sessionIdleMinutes,
        int sessionMaxHours) {

    public record Item(String key, String label, int count) {}

    public record Protection(String key, String label, boolean on, String note) {}

    public record Recent(String type, String label, LocalDateTime occurredAt) {}
}
