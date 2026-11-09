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

/** 사용자가 "기억해 줘"라고 한(또는 화면에서 직접 추가한) 짧은 메모. 이후 대화에서 참고 자료로 모델에 전달된다. */
@Entity
@Table(name = "knowledge_notes", indexes = @Index(name = "idx_notes_owner", columnList = "user_id"))
public class KnowledgeNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 300)
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** chat(대화 중 저장) / ui(화면에서 직접 추가) */
    @Column(nullable = false, length = 8)
    private String source;

    protected KnowledgeNote() {
        // JPA
    }

    public KnowledgeNote(User owner, String content, String source) {
        this.owner = owner;
        this.content = content;
        this.source = source;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getSource() {
        return source;
    }
}
