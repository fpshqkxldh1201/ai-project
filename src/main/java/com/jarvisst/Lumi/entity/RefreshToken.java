package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 발급한 재발급(refresh) 토큰 한 장의 기록. 토큰 문자열 자체는 저장하지 않고 jti(식별자)만 저장한다.
 * 쓸 때마다 새 토큰으로 바꾸고(회전) 이전 것은 ROTATED 로 폐기한다. 폐기된 토큰이 다시 쓰이면(유출 의심)
 * 같은 로그인(familyId)의 토큰을 전부 폐기한다.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_jti", columnList = "jti", unique = true),
        @Index(name = "idx_refresh_family", columnList = "family_id"),
        @Index(name = "idx_refresh_user", columnList = "user_id")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String jti;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** ROTATED(정상 교체), LOGOUT, REUSE(재사용 탐지), FINGERPRINT(다른 기기), MAX_LIFETIME 등. */
    @Column(name = "revoke_reason", length = 20)
    private String revokeReason;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(String jti, String familyId, Long userId, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.jti = jti;
        this.familyId = familyId;
        this.userId = userId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public void revoke(String reason, LocalDateTime at) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokeReason = reason;
        }
    }

    public Long getId() { return id; }
    public String getJti() { return jti; }
    public String getFamilyId() { return familyId; }
    public Long getUserId() { return userId; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
}
