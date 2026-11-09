package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.LoginAuditLog;
import com.jarvisst.Lumi.repository.LoginAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/** 로그아웃도 감사 로그(AAA)에 남긴다. 세션이 무효화되기 전에 호출되므로 Authentication을 여기서 읽는다. */
@Component
public class AuditLogoutHandler implements LogoutHandler {

    private final LoginAuditLogRepository auditLogRepository;
    private final ClientIpResolver clientIpResolver;

    public AuditLogoutHandler(LoginAuditLogRepository auditLogRepository, ClientIpResolver clientIpResolver) {
        this.auditLogRepository = auditLogRepository;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (authentication == null) {
            return;
        }
        String username;
        String provider;
        if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
            String email = oAuth2User.getAttribute("email");
            username = email != null ? email : authentication.getName();
            provider = "GOOGLE";
        } else {
            username = authentication.getName();
            provider = "LOCAL";
        }

        // X-Forwarded-For는 신뢰 가능한 리버스 프록시 없이는 조작 가능하므로 믿지 않는다
        // (ClientIpResolver 참고) — 로그인 성공/실패 로그와 동일한 기준을 쓴다.
        String ip = clientIpResolver.resolve(request);

        auditLogRepository.save(new LoginAuditLog(
                username, provider, LoginAuditLog.EventType.LOGOUT, ip, request.getHeader("User-Agent")));
    }
}
