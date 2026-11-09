package com.jarvisst.Lumi.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * X-Forwarded-For 헤더는 어떤 클라이언트든 자유롭게 써서 보낼 수 있는 "자기 신고" 값이다.
 * 신뢰할 수 있는 리버스 프록시(nginx 등)를 거쳐 들어오는 요청에서만 의미가 있는데, 지금
 * 배포 구조(브라우저 -> 8081 직접 접속, 또는 React 개발 서버 -> Vite 프록시 -> 8081)엔
 * 그런 프록시가 없다. 이 헤더를 그대로 감사 로그(IP)에 쓰면, 누구나 요청에
 * "X-Forwarded-For: 1.2.3.4" 같은 헤더를 직접 실어서 로그를 조작할 수 있다.
 *
 * 그래서 기본값은 이 헤더를 전혀 믿지 않고 request.getRemoteAddr()만 쓴다. 실제로
 * 신뢰 가능한 프록시 뒤에 배치하게 되면 application.properties에
 * security.trusted-proxy.enabled=true 를 추가해서 켤 수 있다 — 그 전까지는 꺼둔 채로
 * 두는 것이 안전하다.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(@Value("${security.trusted-proxy.enabled:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
