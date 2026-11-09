package com.jarvisst.Lumi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 8081 의 아이디/비밀번호 폼 로그인 성공: JWT 쿠키를 내려주고 "/"로 보낸다. */
@Component
public class JwtFormLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtSessionService sessions;

    public JwtFormLoginSuccessHandler(JwtSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        if (!sessions.startLogin(authentication, request, response)) {
            response.sendRedirect(request.getContextPath() + "/login?error");
            return;
        }
        response.sendRedirect(request.getContextPath() + "/");
    }
}
