package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.UserRepository;
import com.jarvisst.Lumi.service.EmotionTrendStore;
import com.jarvisst.Lumi.service.TranslationService;
import com.jarvisst.Lumi.service.LastExchangeStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 로그아웃할 때 남은 흔적 지우기 (정보보안개론 사회공학 장 — 휴지통 뒤지기, 버려지거나 중고로 팔린 PC 분석).
 *
 *  1) 서버 메모리에 보관 중인 사용자의 직전 질문/답변과 최근 감정 흐름을 지운다.
 *  2) 응답 헤더로 브라우저에 캐시·저장소를 비우라고 지시한다(Clear-Site-Data).
 *     이 헤더는 브라우저가 HTTPS 연결에서만 받아들이므로, HTTPS로 배포할 때 효과가 있다(로컬 http에서는 무시됨).
 *
 * 정리 실패가 로그아웃 자체를 막으면 안 되므로 예외는 삼킨다.
 */
@Component
public class LogoutCleanupHandler implements LogoutHandler {

    private final UserRepository userRepository;
    private final LastExchangeStore lastExchangeStore;
    private final EmotionTrendStore emotionTrendStore;
    private final TranslationService translationService;

    public LogoutCleanupHandler(UserRepository userRepository, LastExchangeStore lastExchangeStore,
                                EmotionTrendStore emotionTrendStore, TranslationService translationService) {
        this.userRepository = userRepository;
        this.lastExchangeStore = lastExchangeStore;
        this.emotionTrendStore = emotionTrendStore;
        this.translationService = translationService;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            if (authentication != null) {
                Optional<User> user;
                if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
                    Object sub = oAuth2User.getAttribute("sub");
                    user = sub == null ? Optional.empty()
                            : userRepository.findByProviderAndProviderId(User.Provider.GOOGLE, String.valueOf(sub));
                } else {
                    user = userRepository.findByUsername(authentication.getName());
                }
                user.ifPresent(u -> {
                    lastExchangeStore.clear(u.getId());
                    emotionTrendStore.clear(u.getId());
                    translationService.clear(u.getId());
                });
            }
        } catch (Exception ignored) {
            // 정리는 최선 노력 — 로그아웃은 계속 진행
        }
        if (request.isSecure()) {
            response.setHeader("Clear-Site-Data", "\"cache\", \"storage\"");
        }
    }
}
