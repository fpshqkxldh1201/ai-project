package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 정보보안개론 2주차 '로그 관리 — AAA(Authentication/Authorization/Accounting)'의
 * Accounting(계정 관리/책임 추적성) 개념을 구현한 보안 감사 로그.
 * 누가 언제 어디서 로그인을 시도했고 성공/실패했는지, 로그아웃은 언제 했는지를 남긴다.
 */
@Entity
@Table(name = "login_audit_log")
public class LoginAuditLog {

    public enum EventType {
        LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 시도한 아이디(또는 구글 이메일). 존재하지 않는 아이디로 실패한 경우에도 입력값 그대로 기록. */
    @Column
    private String username;

    @Column
    private String provider;

    @Column(name = "event_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventType eventType;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected LoginAuditLog() {
        // JPA
    }

    public LoginAuditLog(String username, String provider, EventType eventType, String ipAddress, String userAgent) {
        this.username = username;
        this.provider = provider;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getProvider() {
        return provider;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
