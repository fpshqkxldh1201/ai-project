package com.jarvisst.Lumi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/** 로그아웃: 이 로그인의 재발급 토큰을 모두 폐기하고 쿠키를 지운다. */
@Component
public class JwtLogoutHandler implements LogoutHandler {

    private final JwtSessionService sessions;

    public JwtLogoutHandler(JwtSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        sessions.logout(request, response);
    }
}
