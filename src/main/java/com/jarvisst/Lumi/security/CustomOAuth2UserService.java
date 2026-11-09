package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.UserRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 구글 로그인 성공 시 호출된다. 구글이 알려준 사용자 정보(sub/email/name)로
 * 우리 DB의 users 테이블을 찾아보고, 처음 로그인하는 사람이면 새 계정을 만든다(자동 가입).
 *
 * [중요] 구글은 scope에 "openid"를 포함하는 OIDC 제공자다. Spring Security는 OIDC
 * 로그인일 때 일반 OAuth2UserService가 아니라 "OidcUserService" 경로로만 사용자를
 * 로드한다 — SecurityConfig에서 .userService(...)로만 등록해두면 이 클래스는 한 번도
 * 호출되지 않고, 구글 ID 토큰 클레임만으로 만들어진 기본 DefaultOidcUser가 그대로
 * 쓰여서 DB에 사용자가 전혀 등록(insert)되지 않는다. 그 결과 로그인 자체는 성공한
 * 것처럼 보이지만, CurrentUserProvider가 DB에서 해당 계정을 찾지 못해
 * "구글 계정 정보를 찾을 수 없습니다" 예외가 나는 것 — 이번에 겪은 문제의 실제 원인이다.
 * 그래서 이 클래스는 OidcUserService를 상속하고, SecurityConfig에서는
 * .oidcUserService(...)로 등록해야 한다.
 */
@Service
public class CustomOAuth2UserService extends OidcUserService {

    private final UserRepository userRepository;

    public CustomOAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String providerId = oidcUser.getAttribute("sub");
        String email = oidcUser.getAttribute("email");
        String name = oidcUser.getAttribute("name");

        if (providerId == null) {
            throw new OAuth2AuthenticationException("구글 계정 식별자(sub)를 가져오지 못했습니다.");
        }

        User user = userRepository.findByProviderAndProviderId(User.Provider.GOOGLE, providerId)
                .map(existing -> {
                    existing.updateGoogleProfile(email, name);
                    return existing;
                })
                .orElseGet(() -> User.googleUser(providerId, email, name));
        userRepository.save(user);

        // 기존 OidcUser의 ID 토큰/UserInfo는 그대로 유지하고, 권한만 ROLE_USER로 부여한다.
        // nameAttributeKey는 지정하지 않아도 기본값이 "sub"이라 CurrentUserProvider의
        // getAttribute("sub") 조회에는 영향이 없다.
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                oidcUser.getIdToken(), oidcUser.getUserInfo());
    }
}
