package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 정보보안개론 6장(악성 코드) '탐지 및 대응' 단계의 기록 — 무엇을 탐지했고 어떻게 대응했는지 남긴다.
 * (로그인 성공/실패는 기존 LoginAuditLog가 맡고, 이 테이블은 대화·요청 단계의 보안 이벤트를 맡는다.)
 */
@Entity
@Table(name = "security_event", indexes = @Index(name = "idx_security_event_user", columnList = "user_id, occurred_at"))
public class SecurityEvent {

    public enum Type {
        /** 시스템 지시 변조/유출 시도 문장 탐지 (프롬프트 인젝션) */
        PROMPT_INJECTION,
        /** 인젝션이 반복되어 일정 시간 대화를 제한한 것 */
        CHAT_RESTRICTED,
        /** 사용자당 대화 요청 속도 제한에 걸림 */
        CHAT_RATE_LIMIT,
        /** 위험한 링크(실행 파일·위장 주소 등)를 화면/답변에서 제거 */
        LINK_BLOCKED,
        /** 답변에 시스템 프롬프트 유출 표식이 있어 답변을 차단 */
        REPLY_BLOCKED,
        /** IP당 API 요청 속도 제한에 걸림 (웜/DoS 성격의 과다 요청) */
        API_RATE_LIMIT,
        /** 비정상적으로 큰 요청 본문 거부 */
        OVERSIZED_REQUEST,
        /** 같은 세션이 다른 브라우저/IP에서 쓰여 세션을 폐기 (세션 하이재킹 의심) */
        SESSION_ANOMALY,
        /** 유휴/최대 시간 초과로 세션을 폐기 */
        SESSION_EXPIRED,
        /** 동시 처리 한도(사용자별/서버 전체) 초과로 거절 */
        CONCURRENCY_LIMIT,
        /** 주소·쿼리에서 디렉터리 탐색/스캐너/XSS/SQL 삽입/명령 삽입 시도 탐지 */
        WEB_ATTACK_SUSPECTED,
        /** 공격 시도가 반복되어 IP를 잠시 차단 */
        IP_BLOCKED,
        /** 권위 사칭·긴급 압박 등으로 비밀·타인 정보를 요구하는 사회공학 시도 탐지 */
        SOCIAL_ENGINEERING_SUSPECTED,
        /** 대화 입력에 섞인 주민번호·카드번호·비밀 키를 가려서 외부로 보내지 않음 */
        SENSITIVE_INPUT_MASKED,
        /** 신상털기 성격의 검색을 도구 호출 단계에서 거절 */
        PERSON_LOOKUP_BLOCKED,
        /** 기억 노트에 넣으려던 내용이 프롬프트 인젝션·민감정보로 의심되어 거절됨 */
        NOTE_REJECTED,
        /** 업로드된 이미지가 검증(크기·형식 위장·해상도 폭탄·판독 불가)에 실패해 거부 */
        IMAGE_REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 전 요청(IP 단위 제한 등)은 null. */
    @Column(name = "user_id")
    private Long userId;

    // MySQL enum 컬럼은 ddl-auto=update로 값이 추가되지 않으므로, 이벤트 종류를 늘려도 안전하게 문자열 컬럼으로 둔다.
    @Column(name = "event_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Type eventType;

    @Column(length = 300)
    private String detail;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected SecurityEvent() {
        // JPA
    }

    public SecurityEvent(Long userId, Type eventType, String detail, String ipAddress) {
        this.userId = userId;
        this.eventType = eventType;
        this.detail = detail;
        this.ipAddress = ipAddress;
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Type getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
