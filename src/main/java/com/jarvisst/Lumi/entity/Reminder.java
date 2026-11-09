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

@Entity
@Table(name = "reminders")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 권한 관리(정보보안개론 2주차): 리마인더도 등록한 사용자 본인만 조회·완료 처리할 수 있어야 한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User owner;

    @Column(nullable = false)
    private String content;

    @Column(name = "remind_at", nullable = false)
    private LocalDateTime remindAt;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 선제적으로("스스로 말 걸기") 이미 한 번 알렸는지 — 중복 알림 방지용. 기본값 false. */
    @Column(name = "notified")
    private Boolean notified;

    protected Reminder() {
        // JPA
    }

    public Reminder(User owner, String content, LocalDateTime remindAt) {
        this.owner = owner;
        this.content = content;
        this.remindAt = remindAt;
        this.completed = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getRemindAt() {
        return remindAt;
    }

    public boolean isCompleted() {
        return completed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void markCompleted() {
        this.completed = true;
    }

    public boolean isNotified() {
        return notified != null && notified;
    }

    public void markNotified() {
        this.notified = true;
    }
}
