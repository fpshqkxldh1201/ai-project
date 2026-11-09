package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * 권한 관리(정보보안개론 2주차): "시스템의 각 사용자가 적절한 권한으로 적절하게 정보
 * 자산에 접근하도록 통제"하기 위해, 일정·리마인더 등 사용자 소유 데이터를 다루는 모든
 * 서비스가 공통으로 "지금 로그인한 사용자가 누구인지"를 얻는 곳을 여기 하나로 모은다.
 * Function Calling(Gemini)이 호출하는 서비스 메서드들도 전부 같은 HTTP 요청 스레드
 * 안에서 동기적으로 실행되므로, SecurityContextHolder로 언제든 꺼내올 수 있다.
 */
@Component
public class CurrentUserProvider {

    private final UserRepository userRepository;

    public CurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new IllegalStateException("로그인한 사용자 정보를 찾을 수 없습니다.");
        }

        if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
            String providerId = oAuth2User.getAttribute("sub");
            return userRepository.findByProviderAndProviderId(User.Provider.GOOGLE, providerId)
                    .orElseThrow(() -> new IllegalStateException("구글 계정 정보를 찾을 수 없습니다."));
        }

        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("사용자 정보를 찾을 수 없습니다."));
    }
}
