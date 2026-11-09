package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.security.JwtSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/auth/refresh — 접근 토큰을 명시적으로 다시 받는다(재발급 토큰 쿠키 사용, 회전).
 * 평소에는 JwtAuthFilter 가 요청 도중에 알아서 갱신하므로 프론트가 호출할 필요는 없다.
 */
@RestController
public class JwtAuthController {

    private final JwtSessionService sessions;

    public JwtAuthController(JwtSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<Map<String, String>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rt = JwtSessionService.cookieValue(request, JwtSessionService.RT_COOKIE);
        if (rt != null && sessions.authenticateRefresh(rt, request, response, true) != null) {
            return ResponseEntity.ok(Map.of("message", "토큰이 갱신되었습니다."));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "다시 로그인해주세요.", "code", "SESSION_EXPIRED"));
    }
}
