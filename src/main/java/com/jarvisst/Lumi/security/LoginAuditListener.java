package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.LoginAuditLog;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.LoginAuditLogRepository;
import com.jarvisst.Lumi.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 정보보안개론 2주차 '계정 관리(무차별 대입 공격 방어)' + '로그 관리(AAA)' 구현.
 * 폼 로그인(8081), 구글 로그인, React용 POST /api/auth/login — 전부 같은
 * AuthenticationManager(ProviderManager)를 거치므로, 이 하나의 리스너로 세 경로의
 * 로그인 성공/실패를 모두 감사 로그에 남기고, LOCAL 계정의 연속 실패 시 잠금 처리까지 한다.
 */
@Component
public class LoginAuditListener {

    private static final Logger log = LoggerFactory.getLogger(LoginAuditListener.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 15;

    private final LoginAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ClientIpResolver clientIpResolver;
    private final LoginRateLimiter loginRateLimiter;

    public LoginAuditListener(LoginAuditLogRepository auditLogRepository, UserRepository userRepository,
                               ClientIpResolver clientIpResolver, LoginRateLimiter loginRateLimiter) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.clientIpResolver = clientIpResolver;
        this.loginRateLimiter = loginRateLimiter;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        String username = resolveUsername(authentication);
        String provider = resolveProvider(authentication);
        String ip = currentIp();

        auditLogRepository.save(new LoginAuditLog(
                username, provider, LoginAuditLog.EventType.LOGIN_SUCCESS, ip, currentUserAgent()));

        loginRateLimiter.recordSuccess(ip);

        if ("LOCAL".equals(provider) && username != null) {
            userRepository.findByUsername(username).ifPresent(user -> {
                user.registerSuccessfulLogin();
                userRepository.save(user);
            });
        }
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication() != null ? event.getAuthentication().getName() : null;
        String ip = currentIp();

        auditLogRepository.save(new LoginAuditLog(
                username, "LOCAL", LoginAuditLog.EventType.LOGIN_FAILURE, ip, currentUserAgent()));

        // 계정 단위 잠금과는 별개로, 이 IP가 (어떤 아이디로 시도했든) 짧은 시간에 너무 많이
        // 실패하면 분산 크리덴셜 스터핑으로 보고 IP 자체를 차단한다. 존재하지 않는 아이디로
        // 실패한 경우도 포함해야 하므로 username==null 인 경우도 걸러내지 않고 기록한다.
        loginRateLimiter.recordFailure(ip);

        if (username != null) {
            userRepository.findByUsername(username)
                    .filter(u -> u.getProvider() == User.Provider.LOCAL)
                    .ifPresent(user -> {
                        user.registerFailedLogin(MAX_FAILED_ATTEMPTS, LOCK_MINUTES);
                        userRepository.save(user);
                        if (user.isCurrentlyLocked()) {
                            log.warn("계정 잠금: username={}, 연속 실패={}, {}분간 잠금",
                                    username, user.getFailedLoginAttempts(), LOCK_MINUTES);
                        }
                    });
        }
    }

    private String resolveUsername(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
            String email = oAuth2User.getAttribute("email");
            return email != null ? email : authentication.getName();
        }
        return authentication.getName();
    }

    private String resolveProvider(Authentication authentication) {
        return (authentication.getPrincipal() instanceof OAuth2User) ? "GOOGLE" : "LOCAL";
    }

    private String currentIp() {
        return clientIpResolver.resolve(currentRequest());
    }

    private String currentUserAgent() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
