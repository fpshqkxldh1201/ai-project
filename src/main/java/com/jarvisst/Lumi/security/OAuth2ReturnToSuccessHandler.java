package com.jarvisst.Lumi.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * 구글 로그인은 브라우저 전체 페이지 이동(리다이렉트)으로 구글을 거쳐 돌아오기 때문에,
 * 로그인을 시작한 곳이 정적 화면(8081)인지 React 개발 서버(5173)인지에 따라
 * 로그인 성공 후 돌아갈 주소가 달라져야 한다.
 *
 * React 쪽(LoginView)이 구글 로그인 버튼을 누르기 직전에
 * "lumi_return_to=5173" 쿠키를 심어두면, 로그인 성공 시 여기서 그 쿠키를 보고
 * 5173으로 돌려보낸다. 쿠키가 없으면(= 8081 자체 로그인 페이지에서 시작한 경우)
 * 기존대로 "/"(8081의 정적 화면)로 돌려보낸다.
 */
public class OAuth2ReturnToSuccessHandler implements AuthenticationSuccessHandler {

    private static final String RETURN_TO_COOKIE = "lumi_return_to";
    private static final String REACT_DEV_ORIGIN = "http://localhost:5173/";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        String target = "/";

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (RETURN_TO_COOKIE.equals(cookie.getName()) && "5173".equals(cookie.getValue())) {
                    target = REACT_DEV_ORIGIN;
                    Cookie clear = new Cookie(RETURN_TO_COOKIE, "");
                    clear.setPath("/");
                    clear.setMaxAge(0);
                    response.addCookie(clear);
                    break;
                }
            }
        }

        response.sendRedirect(target);
    }
}
