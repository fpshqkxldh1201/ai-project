package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 로그인 계정 하나(일반 아이디/비밀번호 가입 또는 구글 로그인)를 나타낸다.
 * - 일반 가입: provider=LOCAL, username/password 사용, providerId는 null
 * - 구글 로그인: provider=GOOGLE, providerId(구글 sub)/email 사용, password는 null
 * 두 방식을 같은 테이블에서 관리해 "로그인된 사용자 1명"이라는 개념만 심플하게 둔다.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_users_provider_id", columnNames = {"provider", "provider_id"})
        }
)
public class User {

    public enum Provider {
        LOCAL, GOOGLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LOCAL 계정의 로그인 아이디. GOOGLE 계정은 null. */
    @Column
    private String username;

    /** BCrypt로 해시된 비밀번호. GOOGLE 계정은 null(비밀번호 로그인 자체가 없음). */
    @Column
    private String password;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Provider provider;

    /** GOOGLE 계정의 구글 고유 ID(sub). LOCAL 계정은 null. */
    @Column(name = "provider_id")
    private String providerId;

    @Column
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 연속 로그인 실패 횟수(정보보안개론 2주차 '계정 관리' — 무차별 대입 공격 방어). 성공하면 0으로 리셋. */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    /** 이 시각 전까지는 비밀번호가 맞아도 로그인을 거부한다(연속 실패로 잠긴 경우). */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    // ── LUMI CONSOLE 설정 (입력/답변 언어, 음성 성별, TTS on/off) ──
    // 계정에 귀속시켜서 브라우저 localStorage가 아니라 서버(DB)에 저장한다 — 다른 기기/
    // 브라우저에서 로그인해도, 서버를 재시작해도 그대로 유지된다. ddl-auto=update라
    // 기존 행에는 컬럼이 NULL로 채워지므로, nullable=false를 쓰지 않고 getter에서
    // null이면 기본값으로 대체한다(DB 마이그레이션 중 NOT NULL 위반을 피하기 위함).
    @Column(name = "input_lang")
    private String inputLang;

    @Column(name = "response_lang")
    private String responseLang;

    @Column(name = "voice_gender")
    private String voiceGender;

    @Column(name = "tts_enabled")
    private Boolean ttsEnabled;

    /** 버튼을 누르지 않아도 항상 마이크를 켜두고 말하면 바로 대화하는 모드. 기본값 false. */
    @Column(name = "always_listening")
    private Boolean alwaysListening;

    // ── 개인정보 관련 설정(감정 기록 저장 / 먼저 말 걸기). 기존 행은 NULL이라 getter에서 기본값(켜짐)으로 대체한다.
    @Column(name = "emotion_log_enabled")
    private Boolean emotionLogEnabled;

    @Column(name = "proactive_care_enabled")
    private Boolean proactiveCareEnabled;

    protected User() {
        // JPA
    }

    public static User localUser(String username, String hashedPassword) {
        User user = new User();
        user.username = username;
        user.password = hashedPassword;
        user.provider = Provider.LOCAL;
        user.displayName = username;
        user.createdAt = LocalDateTime.now();
        return user;
    }

    public static User googleUser(String providerId, String email, String displayName) {
        User user = new User();
        user.provider = Provider.GOOGLE;
        user.providerId = providerId;
        user.email = email;
        user.displayName = (displayName != null && !displayName.isBlank()) ? displayName : email;
        user.createdAt = LocalDateTime.now();
        return user;
    }

    public void updateGoogleProfile(String email, String displayName) {
        this.email = email;
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public Provider getProvider() {
        return provider;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public boolean isCurrentlyLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public void registerFailedLogin(int maxAttempts, long lockMinutes) {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = LocalDateTime.now().plusMinutes(lockMinutes);
        }
    }

    public void registerSuccessfulLogin() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public String getInputLang() {
        return (inputLang != null && !inputLang.isBlank()) ? inputLang : "ko";
    }

    public String getResponseLang() {
        return (responseLang != null && !responseLang.isBlank()) ? responseLang : "auto";
    }

    public String getVoiceGender() {
        return (voiceGender != null && !voiceGender.isBlank()) ? voiceGender : "female";
    }

    public boolean isTtsEnabled() {
        return ttsEnabled == null || ttsEnabled;
    }

    public boolean isAlwaysListening() {
        return alwaysListening != null && alwaysListening;
    }

    /** null로 넘어온 필드는 기존 값을 그대로 유지한다(부분 업데이트 허용). */
    public void updateSettings(String inputLang, String responseLang, String voiceGender, Boolean ttsEnabled,
                                Boolean alwaysListening) {
        if (inputLang != null && !inputLang.isBlank()) {
            this.inputLang = inputLang;
        }
        if (responseLang != null && !responseLang.isBlank()) {
            this.responseLang = responseLang;
        }
        if (voiceGender != null && !voiceGender.isBlank()) {
            this.voiceGender = voiceGender;
        }
        if (ttsEnabled != null) {
            this.ttsEnabled = ttsEnabled;
        }
        if (alwaysListening != null) {
            this.alwaysListening = alwaysListening;
        }
    }

    public boolean isEmotionLogEnabled() {
        return emotionLogEnabled == null || emotionLogEnabled;
    }

    public boolean isProactiveCareEnabled() {
        return proactiveCareEnabled == null || proactiveCareEnabled;
    }

    /** null이면 기존 값을 유지한다(부분 업데이트). */
    public void updatePrivacySettings(Boolean emotionLogEnabled, Boolean proactiveCareEnabled) {
        if (emotionLogEnabled != null) {
            this.emotionLogEnabled = emotionLogEnabled;
        }
        if (proactiveCareEnabled != null) {
            this.proactiveCareEnabled = proactiveCareEnabled;
        }
    }
}
