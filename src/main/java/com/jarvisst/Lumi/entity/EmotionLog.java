package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 감정 시계열 로그 한 줄. 대화 "내용"은 저장하지 않고 분석된 수치(감정 분류, 긍정도, 활성도, 확신도)만 남긴다.
 * 감정은 민감한 정보라서 사용자별로 분리하고, 설정에서 끄거나 전부 지울 수 있으며, 오래된 기록은 자동 삭제한다.
 */
@Entity
@Table(name = "emotion_logs", indexes = @Index(name = "idx_emotion_logs_owner_time", columnList = "user_id, created_at"))
public class EmotionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 정규화된 감정 이름(허용 목록에 있는 값만 들어온다). */
    @Column(nullable = false, length = 24)
    private String mood;

    @Column(nullable = false)
    private double valence;

    @Column(nullable = false)
    private double arousal;

    @Column(nullable = false)
    private double confidence;

    /** text / image / both — 어떤 입력에서 읽은 감정인지(사진 원본은 저장하지 않는다). */
    @Column(nullable = false, length = 8)
    private String source;

    protected EmotionLog() {
        // JPA
    }

    public EmotionLog(User owner, String mood, double valence, double arousal, double confidence, String source) {
        this.owner = owner;
        this.createdAt = LocalDateTime.now();
        this.mood = mood;
        this.valence = valence;
        this.arousal = arousal;
        this.confidence = confidence;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getMood() {
        return mood;
    }

    public double getValence() {
        return valence;
    }

    public double getArousal() {
        return arousal;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getSource() {
        return source;
    }
}
