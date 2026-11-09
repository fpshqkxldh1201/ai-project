package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Lumi가 사용자 입력 없이 "스스로 말을 거는" 한 마디를 담아두는 큐.
 * 리마인더/일정 임박 알림이 지금의 발생원이고, 앞으로 생길 다른 백그라운드 작업(검색 결과,
 * 긴 작업 완료 등)도 ProactiveNotificationService#queue 하나만 호출하면 같은 경로로
 * 프런트엔드에 전달되고(/api/notifications/pending) 음성으로 먼저 안내된다.
 */
@Entity
@Table(name = "proactive_notifications")
public class ProactiveNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 외국어 문구일 때의 한국어 번역(화면에만 표시하고 음성으로는 읽지 않는다). 없으면 null. */
    @Column(length = 1000)
    private String translation;

    /** 프런트엔드가 폴링해서 이미 가져갔는지 — 가져가면 다시 내려주지 않는다. */
    @Column(name = "delivered")
    private Boolean delivered;

    protected ProactiveNotification() {
        // JPA
    }

    public ProactiveNotification(User owner, String message) {
        this.owner = owner;
        this.message = message;
        this.createdAt = LocalDateTime.now();
        this.delivered = false;
    }

    public ProactiveNotification(User owner, String message, String translation) {
        this(owner, message);
        this.translation = translation;
    }

    public String getTranslation() {
        return translation;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isDelivered() {
        return delivered != null && delivered;
    }

    public void markDelivered() {
        this.delivered = true;
    }
}
