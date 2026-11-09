package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 아이디/비밀번호 로그인(폼 로그인) 시 Spring Security가 호출하는 서비스.
 * GOOGLE로 가입된 계정(password가 없는 계정)은 이 방식으로 로그인할 수 없도록 막는다.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 아이디입니다."));

        if (user.getProvider() != User.Provider.LOCAL || user.getPassword() == null) {
            throw new UsernameNotFoundException("이 아이디는 구글 로그인으로 가입되어 비밀번호 로그인을 사용할 수 없습니다.");
        }

        // 계정 관리(정보보안개론 2주차): 연속 로그인 실패로 잠긴 계정은 비밀번호가 맞아도 거부한다.
        // Spring Security의 PreAuthenticationChecks가 accountLocked=true를 보고 비밀번호 검증 전에
        // LockedException을 던진다.
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .accountLocked(user.isCurrentlyLocked())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
    }
}
