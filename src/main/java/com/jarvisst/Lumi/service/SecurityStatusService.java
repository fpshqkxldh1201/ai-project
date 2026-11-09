package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.security.SecurityStatusResponse;
import com.jarvisst.Lumi.dto.security.SecurityStatusResponse.Item;
import com.jarvisst.Lumi.dto.security.SecurityStatusResponse.Protection;
import com.jarvisst.Lumi.dto.security.SecurityStatusResponse.Recent;
import com.jarvisst.Lumi.entity.SecurityEvent;
import com.jarvisst.Lumi.entity.SecurityEvent.Type;
import com.jarvisst.Lumi.repository.SecurityEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 화면용 '현재 보안 상태' 요약. 정보보안개론의 탐지·대응 기록(SecurityEvent)을 사용자가 한눈에 보게 한다.
 * 상태 등급: 24시간 안에 이벤트가 없으면 SECURE, 있으면 NOTICE, 세션 폐기·대화 제한·IP 차단 같은
 * '실제로 막은' 이벤트가 있으면 ALERT.
 */
@Service
public class SecurityStatusService {

    /** 실제로 요청을 끊거나 제한한 이벤트 — 하나라도 있으면 ALERT. */
    static final Set<Type> SEVERE = Set.of(Type.CHAT_RESTRICTED, Type.IP_BLOCKED, Type.SESSION_ANOMALY, Type.REPLY_BLOCKED);

    /** 이벤트 종류 → 화면 묶음 (순서 = 표시 순서). */
    private static final Map<String, String> CATEGORY_LABEL = new LinkedHashMap<>();
    private static final Map<Type, String> CATEGORY_OF = new EnumMap<>(Type.class);
    private static final Map<Type, String> TYPE_LABEL = new EnumMap<>(Type.class);

    static {
        CATEGORY_LABEL.put("injection", "프롬프트 인젝션");
        CATEGORY_LABEL.put("web", "웹 공격 시도");
        CATEGORY_LABEL.put("rate", "과다 요청");
        CATEGORY_LABEL.put("session", "세션 이상");
        CATEGORY_LABEL.put("link", "위험 링크");
        CATEGORY_LABEL.put("data", "민감정보·사칭");
        CATEGORY_LABEL.put("upload", "비정상 업로드");

        map("injection", Type.PROMPT_INJECTION, "프롬프트 인젝션 탐지");
        map("injection", Type.CHAT_RESTRICTED, "대화 일시 제한");
        map("injection", Type.REPLY_BLOCKED, "답변 차단(유출 표식)");
        map("injection", Type.NOTE_REJECTED, "노트 저장 거절");
        map("web", Type.WEB_ATTACK_SUSPECTED, "웹 공격 시도 탐지");
        map("web", Type.IP_BLOCKED, "공격 반복으로 IP 차단");
        map("rate", Type.CHAT_RATE_LIMIT, "대화 속도 제한");
        map("rate", Type.API_RATE_LIMIT, "API 속도 제한");
        map("rate", Type.CONCURRENCY_LIMIT, "동시 처리 한도 초과");
        map("session", Type.SESSION_ANOMALY, "세션 하이재킹 의심");
        map("session", Type.SESSION_EXPIRED, "세션 만료");
        map("link", Type.LINK_BLOCKED, "위험 링크 제거");
        map("data", Type.SENSITIVE_INPUT_MASKED, "민감정보 가림");
        map("data", Type.SOCIAL_ENGINEERING_SUSPECTED, "사회공학 시도 탐지");
        map("data", Type.PERSON_LOOKUP_BLOCKED, "신상 검색 거절");
        map("upload", Type.OVERSIZED_REQUEST, "과대 요청 거부");
        map("upload", Type.IMAGE_REJECTED, "이미지 검증 실패");
    }

    private static void map(String category, Type type, String label) {
        CATEGORY_OF.put(type, category);
        TYPE_LABEL.put(type, label);
    }

    private final SecurityEventRepository repository;
    private final int idleMinutes;
    private final int maxHours;
    private final boolean bindIp;

    public SecurityStatusService(SecurityEventRepository repository,
                                 @Value("${security.session.idle-minutes:30}") int idleMinutes,
                                 @Value("${security.session.max-hours:12}") int maxHours,
                                 @Value("${security.session.bind-ip:false}") boolean bindIp) {
        this.repository = repository;
        this.idleMinutes = idleMinutes;
        this.maxHours = maxHours;
        this.bindIp = bindIp;
    }

    /** secureConnection: 이 요청이 HTTPS(또는 신뢰된 프록시 뒤의 HTTPS)로 들어왔는가. */
    public SecurityStatusResponse statusFor(Long userId, boolean secureConnection) {
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        CATEGORY_LABEL.keySet().forEach(k -> byCategory.put(k, 0));
        int total = 0;
        boolean severe = false;

        for (Object[] row : repository.countByTypeSince(userId, LocalDateTime.now().minusHours(24))) {
            Type type = row[0] instanceof Type t ? t : parse(row[0]);
            int count = ((Number) row[1]).intValue();
            if (type == null || count <= 0) continue;
            total += count;
            severe |= SEVERE.contains(type);
            byCategory.merge(CATEGORY_OF.getOrDefault(type, "data"), count, Integer::sum);
        }

        List<Item> categories = new ArrayList<>();
        byCategory.forEach((k, v) -> {
            if (v > 0) categories.add(new Item(k, CATEGORY_LABEL.get(k), v));
        });

        List<Recent> recent = new ArrayList<>();
        for (SecurityEvent e : repository.findTop5ByUserIdOrderByOccurredAtDesc(userId)) {
            recent.add(new Recent(e.getEventType().name(), TYPE_LABEL.getOrDefault(e.getEventType(), "보안 이벤트"), e.getOccurredAt()));
        }

        String level = total == 0 ? "SECURE" : severe ? "ALERT" : "NOTICE";
        String headline = switch (level) {
            case "SECURE" -> "최근 24시간 동안 탐지된 위협이 없어요";
            case "NOTICE" -> "최근 24시간 동안 " + total + "건을 탐지하고 자동으로 처리했어요";
            default -> "최근 24시간 동안 " + total + "건을 탐지했고, 일부는 차단·제한했어요";
        };

        List<Protection> protections = List.of(
                new Protection("https", "HTTPS 연결", secureConnection,
                        secureConnection ? "암호화된 연결" : "암호화되지 않은 연결(로컬 개발이면 정상)"),
                new Protection("csrf", "CSRF 보호", true, "쿠키 더블 서브밋 토큰"),
                new Protection("headers", "보안 헤더", true, "CSP · HSTS · 클릭재킹 방지"),
                new Protection("session", "세션 보호", true, "유휴 " + idleMinutes + "분 · 최대 " + maxHours + "시간 · 브라우저 변경 감지"),
                new Protection("iplock", "세션 IP 고정", bindIp, bindIp ? "IP가 바뀌면 세션 폐기" : "꺼짐(모바일 네트워크 전환 대비 기본값)"),
                new Protection("rate", "속도·동시 요청 제한", true, "사용자·IP별 제한"),
                new Protection("injection", "인젝션·사칭 방어", true, "입력 검사 · 반복 시 대화 제한"),
                new Protection("mask", "민감정보 가림", true, "주민번호·카드·키는 외부로 보내기 전에 가림"),
                new Protection("link", "위험 링크 차단", true, "실행 파일·위장 주소 제거"),
                new Protection("image", "이미지 검증", true, "형식·크기 확인 후 재인코딩, 저장 안 함"));

        return new SecurityStatusResponse(level, headline, total, categories, protections, recent, idleMinutes, maxHours);
    }

    private static Type parse(Object raw) {
        try {
            return raw == null ? null : Type.valueOf(raw.toString());
        } catch (IllegalArgumentException e) {
            return null; // 예전 버전이 남긴 모르는 종류는 무시
        }
    }
}
