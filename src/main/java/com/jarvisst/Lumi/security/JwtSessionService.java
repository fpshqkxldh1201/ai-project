package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.RefreshToken;
import com.jarvisst.Lumi.entity.SecurityEvent;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.RefreshTokenRepository;
import com.jarvisst.Lumi.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 로그인 세션의 중심: 발급 · 검증 · 회전 · 폐기.
 *
 * 구조
 *  - 접근 토큰(AT, 기본 15분): 요청마다 쿠키 LUMI_AT 로 오고 서명만 확인한다(DB 조회 없음, 사용자만 읽음).
 *  - 재발급 토큰(RT): 쿠키 LUMI_RT. AT 가 만료됐을 때만 쓰이고, 쓰면 새 RT 로 바뀐다(회전). DB 에 jti 를 기록해 폐기할 수 있다.
 *  - 두 쿠키 모두 HttpOnly(JS 가 못 읽음 → XSS 로 토큰 탈취 불가), SameSite=Lax, HTTPS 면 Secure.
 *
 * 기존 세션 방어를 그대로 잇는다
 *  - 기기 바인딩: 토큰에 User-Agent 해시(fp)를 넣고, 다른 브라우저에서 쓰이면 거부 + 그 로그인 전체 폐기.
 *  - 유휴 만료: RT 는 security.session.idle-minutes(기본 30분) 안에 써야 유효(쓸 때마다 갱신).
 *  - 최대 수명: 로그인 후 security.session.max-hours(기본 12시간)가 지나면 무조건 재로그인.
 *  - 재사용 탐지: 이미 교체된 RT 가 (동시 요청 유예 10초 뒤에) 다시 쓰이면 유출로 보고 같은 로그인의 토큰을 전부 폐기.
 *
 * 알림 폴링 같은 '수동 요청'은 AT 가 만료돼도 RT 가 유효하면 통과시키되 토큰을 바꾸지 않는다(유휴 시간을 늘리지 않기 위해).
 */
@Service
public class JwtSessionService {

    private static final Logger log = LoggerFactory.getLogger(JwtSessionService.class);

    public static final String AT_COOKIE = "LUMI_AT";
    public static final String RT_COOKIE = "LUMI_RT";
    /** 회전 직후 같은 RT 를 들고 온 동시 요청(여러 탭·병렬 fetch)을 재사용 공격으로 오인하지 않도록 두는 유예. */
    private static final long ROTATION_GRACE_SEC = 10;

    private final JwtTokenService jwt;
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final SecurityEventService securityEvents;
    private final ClientIpResolver clientIpResolver;
    private final long accessSec;
    private final long idleSec;
    private final long maxSec;
    private final boolean forceSecure;

    public JwtSessionService(JwtTokenService jwt, RefreshTokenRepository refreshTokens, UserRepository users,
                             SecurityEventService securityEvents, ClientIpResolver clientIpResolver,
                             @Value("${security.jwt.access-minutes:15}") long accessMinutes,
                             @Value("${security.session.idle-minutes:30}") long idleMinutes,
                             @Value("${security.session.max-hours:12}") long maxHours,
                             @Value("${security.jwt.cookie-secure:false}") boolean forceSecure) {
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.securityEvents = securityEvents;
        this.clientIpResolver = clientIpResolver;
        this.accessSec = accessMinutes * 60;
        this.idleSec = idleMinutes * 60;
        this.maxSec = maxHours * 3600;
        this.forceSecure = forceSecure;
    }

    // ───────────────────────── 로그인 ─────────────────────────

    /** 로그인에 성공한 사용자에게 새 로그인(family)을 만들어 AT·RT 쿠키를 내려준다. */
    @Transactional
    public void startLogin(User user, HttpServletRequest request, HttpServletResponse response) {
        String sid = UUID.randomUUID().toString();
        long now = nowSec();
        issuePair(user, sid, now, request, response);
    }

    /**
     * 폼 로그인·구글 로그인이 끝난 Authentication 에서 사용자를 찾아 JWT 로그인을 시작한다.
     * 이미 만들어진 HTTP 세션(로그인 과정에서만 쓴 것)은 폐기해서, 이후에는 JWT 쿠키만으로 인증된다.
     */
    @Transactional
    public boolean startLogin(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        Optional<User> user;
        if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.user.OAuth2User o) {
            Object sub = o.getAttribute("sub");
            user = sub == null ? Optional.empty() : users.findByProviderAndProviderId(User.Provider.GOOGLE, String.valueOf(sub));
        } else {
            user = users.findByUsername(authentication.getName());
        }
        if (user.isEmpty()) {
            log.warn("[JWT] 로그인 성공 후 사용자 정보를 찾지 못했습니다: {}", authentication.getName());
            return false;
        }
        startLogin(user.get(), request, response);
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return true;
    }

    private void issuePair(User user, String sid, long startSec, HttpServletRequest request, HttpServletResponse response) {
        long now = nowSec();
        String fp = fingerprint(request);
        long limit = startSec + maxSec;
        long atExp = Math.min(now + accessSec, limit);
        long rtExp = Math.min(now + idleSec, limit);
        String rtJti = UUID.randomUUID().toString();

        refreshTokens.save(new RefreshToken(rtJti, sid, user.getId(), toTime(now), toTime(rtExp)));
        String at = jwt.issue("at", String.valueOf(user.getId()), UUID.randomUUID().toString(), sid, fp, now, atExp, startSec);
        String rt = jwt.issue("rt", String.valueOf(user.getId()), rtJti, sid, fp, now, rtExp, startSec);
        addCookie(response, request, AT_COOKIE, at, Duration.ofSeconds(atExp - now));
        addCookie(response, request, RT_COOKIE, rt, Duration.ofSeconds(rtExp - now));
    }

    // ───────────────────────── 요청마다 검증 ─────────────────────────

    /** AT 쿠키(또는 Authorization: Bearer)가 올바르면 인증 객체를, 아니면 null. */
    public Authentication authenticateAccess(String accessToken, HttpServletRequest request) {
        JwtTokenService.Claims c = jwt.parse(accessToken, "at", nowSec());
        if (c == null || !c.fp().equals(fingerprint(request))) {
            return null;
        }
        return loadUser(c.subject()).filter(u -> !u.isCurrentlyLocked()).map(this::toAuthentication).orElse(null);
    }

    /**
     * RT 로 인증한다. rotate=true 면 토큰을 새로 바꾸고(쿠키 갱신), false 면 유효성만 확인한다(수동 요청용).
     * 실패하면 null 이고, rotate=true 인 실패에서는 쿠키를 지운다.
     */
    @Transactional
    public Authentication authenticateRefresh(String refreshToken, HttpServletRequest request,
                                              HttpServletResponse response, boolean rotate) {
        long now = nowSec();
        JwtTokenService.Claims c = jwt.parse(refreshToken, "rt", now);
        if (c == null) {
            return fail(request, response, rotate);
        }
        Optional<RefreshToken> found = refreshTokens.findByJti(c.jti());
        Optional<User> user = loadUser(c.subject());
        if (found.isEmpty() || user.isEmpty() || user.get().isCurrentlyLocked()) {
            return fail(request, response, rotate);
        }
        RefreshToken rec = found.get();
        LocalDateTime nowTime = LocalDateTime.now();

        if (!c.fp().equals(fingerprint(request))) {
            refreshTokens.revokeFamily(rec.getFamilyId(), "FINGERPRINT", nowTime);
            securityEvents.record(user.get().getId(), SecurityEvent.Type.SESSION_ANOMALY,
                    "다른 브라우저/기기에서 같은 로그인 토큰이 사용됨 (JWT)", ip(request));
            return fail(request, response, rotate);
        }
        if (now - c.start() > maxSec) {
            rec.revoke("MAX_LIFETIME", nowTime);
            securityEvents.record(user.get().getId(), SecurityEvent.Type.SESSION_EXPIRED, "로그인 최대 사용 시간 초과 (JWT)", ip(request));
            return fail(request, response, rotate);
        }
        if (rec.getRevokedAt() != null) {
            boolean graceOk = "ROTATED".equals(rec.getRevokeReason())
                    && Duration.between(rec.getRevokedAt(), nowTime).getSeconds() <= ROTATION_GRACE_SEC;
            if (graceOk) {
                // 방금 다른 요청이 이미 교체함 — 이 요청은 통과시키고, 새 쿠키는 그 요청의 응답이 브라우저에 심는다.
                return toAuthentication(user.get());
            }
            refreshTokens.revokeFamily(rec.getFamilyId(), "REUSE", nowTime);
            securityEvents.record(user.get().getId(), SecurityEvent.Type.SESSION_ANOMALY,
                    "이미 교체/폐기된 재발급 토큰이 다시 사용됨 — 로그인 전체 폐기 (JWT)", ip(request));
            return fail(request, response, rotate);
        }
        if (!rotate) {
            return toAuthentication(user.get());
        }
        rec.revoke("ROTATED", nowTime);
        issuePair(user.get(), c.sid(), c.start(), request, response);
        return toAuthentication(user.get());
    }

    private Authentication fail(HttpServletRequest request, HttpServletResponse response, boolean rotate) {
        if (rotate) {
            clearCookies(request, response);
        }
        return null;
    }

    // ───────────────────────── 로그아웃 ─────────────────────────

    /** RT 가 가리키는 로그인(family)을 폐기하고 쿠키를 지운다. 토큰이 없거나 이상해도 쿠키는 지운다. */
    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            String rt = cookieValue(request, RT_COOKIE);
            JwtTokenService.Claims c = jwt.parse(rt, "rt", nowSec(), true); // 만료됐어도 폐기는 해 준다
            if (c != null) {
                refreshTokens.revokeFamily(c.sid(), "LOGOUT", LocalDateTime.now());
            }
        } catch (Exception e) {
            log.warn("[JWT] 로그아웃 중 토큰 폐기 실패 — {}", e.toString());
        }
        clearCookies(request, response);
    }

    // ───────────────────────── 쿠키·도우미 ─────────────────────────

    public static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void addCookie(HttpServletResponse response, HttpServletRequest request, String name, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true).secure(forceSecure || request.isSecure()).sameSite("Lax")
                .path("/").maxAge(maxAge).build().toString());
    }

    public void clearCookies(HttpServletRequest request, HttpServletResponse response) {
        for (String name : new String[]{AT_COOKIE, RT_COOKIE}) {
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, "")
                    .httpOnly(true).secure(forceSecure || request.isSecure()).sameSite("Lax")
                    .path("/").maxAge(0).build().toString());
        }
    }

    private Optional<User> loadUser(String subject) {
        try {
            return users.findById(Long.parseLong(subject));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 기존 코드(CurrentUserProvider·AuthController 등)가 그대로 동작하도록, 구글 사용자는 OAuth2User 로,
     * 일반 사용자는 아이디(String)를 principal 로 하는 인증 객체를 만든다.
     */
    public Authentication toAuthentication(User user) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.getProvider() == User.Provider.GOOGLE) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("sub", user.getProviderId());
            if (user.getEmail() != null) {
                attrs.put("email", user.getEmail());
            }
            if (user.getDisplayName() != null) {
                attrs.put("name", user.getDisplayName());
            }
            return new UsernamePasswordAuthenticationToken(new DefaultOAuth2User(authorities, attrs, "sub"), null, authorities);
        }
        return new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
    }

    private String ip(HttpServletRequest request) {
        try {
            return clientIpResolver.resolve(request);
        } catch (Exception e) {
            return null;
        }
    }

    static String fingerprint(HttpServletRequest request) {
        try {
            String ua = request.getHeader("User-Agent");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((ua == null ? "" : ua).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception e) {
            return "";
        }
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    private static LocalDateTime toTime(long epochSec) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSec), java.time.ZoneId.systemDefault());
    }

    /** 만료된 재발급 토큰 기록을 하루에 한 번 정리한다(만료 1일 뒤 삭제). */
    @Scheduled(cron = "0 17 4 * * *")
    @Transactional
    public void purgeExpired() {
        int n = refreshTokens.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
        if (n > 0) {
            log.info("[JWT] 만료된 재발급 토큰 기록 {}건 정리", n);
        }
    }
}
