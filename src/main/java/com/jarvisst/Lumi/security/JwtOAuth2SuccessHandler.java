package com.jarvisst.Lumi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 구글 로그인 성공: JWT 쿠키를 내려준 뒤, 기존처럼 8081("/") 또는 5173(React)으로 돌려보낸다.
 * (구글 인증 과정에서만 잠깐 쓴 HTTP 세션은 JwtSessionService.startLogin 에서 폐기한다.)
 */
@Component
public class JwtOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final JwtSessionService sessions;
    private final OAuth2ReturnToSuccessHandler returnTo = new OAuth2ReturnToSuccessHandler();

    public JwtOAuth2SuccessHandler(JwtSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        if (!sessions.startLogin(authentication, request, response)) {
            response.sendRedirect(request.getContextPath() + "/login?error");
            return;
        }
        returnTo.onAuthenticationSuccess(request, response, authentication);
    }
}
