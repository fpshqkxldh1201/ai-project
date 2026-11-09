package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.SecurityEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 세션 하이재킹 대응 (정보보안개론 3장 네트워크 보안: 세션 하이재킹).
 *
 * 세션 ID(쿠키)가 유출되면 공격자는 비밀번호 없이 그 사용자로 행세할 수 있다. 쿠키 자체를 훔치는 길은
 * HttpOnly/Secure/HSTS/CSP가 막고, 이 필터는 "훔친 뒤에 쓰는 것"을 어렵게 만든다.
 *
 *  1) 기기 바인딩: 세션을 처음 쓴 브라우저(User-Agent 해시)와 다른 브라우저에서 같은 세션이 쓰이면
 *     세션을 폐기한다. (security.session.bind-ip=true 로 IP까지 묶을 수 있다. 기본은 꺼짐 —
 *     집/학교 Wi-Fi와 LTE를 오가면 IP가 바뀌는 정상 사용자도 로그아웃되기 때문)
 *  2) 유휴 만료: 마지막 '사용자 활동' 이후 security.session.idle-minutes(기본 30분)가 지나면 폐기.
 *     알림 폴링(GET /api/notifications/pending)은 사용자가 가만히 있어도 30초마다 가므로 활동으로 치지 않는다.
 *     (톰캣 기본 만료는 폴링만으로도 계속 연장되어 사실상 만료되지 않기 때문에 따로 둔다)
 *  3) 절대 수명: 활동이 계속 있어도 로그인 후 security.session.max-hours(기본 12시간)가 지나면 폐기 → 재로그인.
 *
 * 로그인 직전 세션 ID 교체(세션 고정 공격 방지)는 AuthController.apiLogin과 Spring Security 폼/구글 로그인이 맡는다.
 */
@Component
public class SessionGuardFilter extends OncePerRequestFilter {

    static final String ATTR_UA = "lumi.session.ua";
    static final String ATTR_IP = "lumi.session.ip";
    static final String ATTR_CREATED = "lumi.session.created";
    static final String ATTR_ACTIVE = "lumi.session.active";
    private static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    private final long idleMs;
    private final long maxMs;
    private final boolean bindIp;
    private final ClientIpResolver clientIpResolver;
    private final SecurityEventService securityEvents;

    public SessionGuardFilter(@Value("${security.session.idle-minutes:30}") long idleMinutes,
                              @Value("${security.session.max-hours:12}") long maxHours,
                              @Value("${security.session.bind-ip:false}") boolean bindIp,
                              ClientIpResolver clientIpResolver, SecurityEventService securityEvents) {
        this.idleMs = idleMinutes * 60_000L;
        this.maxMs = maxHours * 3_600_000L;
        this.bindIp = bindIp;
        this.clientIpResolver = clientIpResolver;
        this.securityEvents = securityEvents;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        String path = request.getRequestURI().substring(request.getContextPath().length());

        // 로그인 전 세션(OAuth2 진행 중 등)이거나 로그아웃/오류 경로면 검사하지 않는다.
        if (session == null || session.getAttribute(SECURITY_CONTEXT_ATTR) == null
                || path.equals("/logout") || path.equals("/error")) {
            chain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        String ua = fingerprint(request.getHeader("User-Agent"));
        String ip = clientIpResolver.resolve(request);

        Object storedUa = session.getAttribute(ATTR_UA);
        if (storedUa == null) {
            // 로그인 직후 첫 요청: 이 세션의 기준값을 기록한다.
            session.setAttribute(ATTR_UA, ua);
            session.setAttribute(ATTR_IP, ip == null ? "" : ip);
            session.setAttribute(ATTR_CREATED, now);
            session.setAttribute(ATTR_ACTIVE, now);
            chain.doFilter(request, response);
            return;
        }

        String reason = null;
        boolean anomaly = false;
        if (!storedUa.equals(ua)) {
            reason = "다른 브라우저/기기에서 같은 세션이 사용됨";
            anomaly = true;
        } else if (bindIp && ip != null && !ip.equals(session.getAttribute(ATTR_IP))) {
            reason = "세션을 만든 IP와 다른 IP에서 사용됨";
            anomaly = true;
        } else if (now - asLong(session.getAttribute(ATTR_CREATED), now) > maxMs) {
            reason = "세션 최대 사용 시간 초과";
        } else if (now - asLong(session.getAttribute(ATTR_ACTIVE), now) > idleMs) {
            reason = "장시간 활동 없음";
        }

        if (reason != null) {
            securityEvents.record(anomaly ? SecurityEvent.Type.SESSION_ANOMALY : SecurityEvent.Type.SESSION_EXPIRED,
                    reason + " (" + path + ")");
            session.invalidate();
            reject(request, response, path, anomaly);
            return;
        }

        if (!isPassive(request, path)) {
            session.setAttribute(ATTR_ACTIVE, now);
        }
        chain.doFilter(request, response);
    }

    /** 사용자 조작 없이 브라우저가 알아서 보내는 요청 — 유휴 시간을 연장하지 않는다. */
    private boolean isPassive(HttpServletRequest request, String path) {
        return "GET".equals(request.getMethod()) && path.equals("/api/notifications/pending");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String path, boolean anomaly)
            throws IOException {
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            String message = anomaly
                    ? "보안을 위해 로그아웃되었습니다. 다시 로그인해주세요."
                    : "오래 사용하지 않아 로그아웃되었습니다. 다시 로그인해주세요.";
            response.getWriter().write("{\"error\":\"" + message + "\",\"code\":\"SESSION_EXPIRED\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/login?expired");
        }
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Long l ? l : fallback;
    }

    private static String fingerprint(String userAgent) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((userAgent == null ? "" : userAgent).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception e) {
            return "";
        }
    }
}
