package com.jarvisst.Lumi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청마다 JWT 쿠키(또는 Authorization: Bearer)로 로그인 상태를 만든다.
 *
 *  1) AT 가 올바르면 그대로 인증(DB 조회 없음).
 *  2) AT 가 없거나 만료됐고 RT 가 있으면 RT 로 인증하면서 토큰을 새로 바꾼다(프론트는 아무것도 안 해도 로그인이 이어짐).
 *     단, 알림 폴링 같은 수동 요청은 RT 가 유효한지만 보고 토큰은 바꾸지 않는다(유휴 시간을 늘리지 않기 위해).
 *  3) 아무것도 없으면 인증 없이 통과 → 뒤의 인가 단계가 401(API) 또는 로그인 화면으로 보낸다.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtSessionService sessions;

    public JwtAuthFilter(JwtSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated() && !(existing instanceof AnonymousAuthenticationToken)) {
            chain.doFilter(request, response); // 예: 구글 로그인 진행 중 세션 인증
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        Authentication auth = null;

        String at = JwtSessionService.cookieValue(request, JwtSessionService.AT_COOKIE);
        if (at == null) {
            String header = request.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                at = header.substring(7).trim();
            }
        }
        if (at != null) {
            auth = sessions.authenticateAccess(at, request);
        }
        if (auth == null && !path.equals("/api/auth/refresh")) {
            String rt = JwtSessionService.cookieValue(request, JwtSessionService.RT_COOKIE);
            if (rt != null) {
                auth = sessions.authenticateRefresh(rt, request, response, !isPassive(request, path));
            }
        }

        if (auth != null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    /** 사용자 조작 없이 브라우저가 알아서 보내는 요청 — SessionGuardFilter 와 같은 기준. */
    private static boolean isPassive(HttpServletRequest request, String path) {
        return "GET".equals(request.getMethod()) && path.equals("/api/notifications/pending");
    }
}
