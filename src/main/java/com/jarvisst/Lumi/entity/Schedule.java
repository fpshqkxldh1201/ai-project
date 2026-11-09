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
@Table(name = "schedules")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 권한 관리(정보보안개론 2주차): 일정은 등록한 사용자 본인만 조회·등록할 수 있어야 하므로
     * 소유자를 반드시 기록한다. 로그인 기능 추가 전에 만들어진 기존 행은 owner가 비어 있을 수
     * 있는데, 그런 행은 더 이상 누구의 조회 결과에도 포함되지 않는다(소유자 불명 데이터는
     * 노출하지 않는 쪽이 안전).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User owner;

    @Column(nullable = false)
    private String title;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    private String memo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 선제적으로("스스로 말 걸기") 시작이 임박했다고 이미 한 번 알렸는지 — 중복 알림 방지용. */
    @Column(name = "notified")
    private Boolean notified;

    protected Schedule() {
        // JPA
    }

    public Schedule(User owner, String title, LocalDateTime startTime, LocalDateTime endTime, String memo) {
        this.owner = owner;
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
        this.memo = memo;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public String getMemo() {
        return memo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isNotified() {
        return notified != null && notified;
    }

    public void markNotified() {
        this.notified = true;
    }
}
