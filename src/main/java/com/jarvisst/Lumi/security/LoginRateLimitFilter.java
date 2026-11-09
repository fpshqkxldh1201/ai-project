package com.jarvisst.Lumi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 로그인 엔드포인트(폼 로그인 /login, React용 /api/auth/login) 앞단에서
 * LoginRateLimiter로 이 IP가 이미 차단된 상태인지 먼저 확인한다. 차단된 IP는
 * AuthenticationManager(비밀번호 BCrypt 검증 포함)까지 가지 않고 즉시 429로
 * 끝낸다 — 차단 이후의 반복 시도가 서버에 추가 부담(CPU, DB 조회)을 주지 않는다.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public LoginRateLimitFilter(LoginRateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean isLoginAttempt = "POST".equalsIgnoreCase(request.getMethod())
                && ("/login".equals(uri) || "/api/auth/login".equals(uri));

        if (isLoginAttempt) {
            String ip = clientIpResolver.resolve(request);
            if (rateLimiter.isBlocked(ip)) {
                if (uri.startsWith("/api/")) {
                    // HttpServletResponse에는 429(Too Many Requests)용 SC_* 상수가 없다
                    // (서블릿 스펙에 추가된 적이 없음) — 숫자를 직접 써야 한다.
                    response.setStatus(429);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"error\":\"로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
                } else {
                    response.sendRedirect(request.getContextPath() + "/login?error");
                }
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
