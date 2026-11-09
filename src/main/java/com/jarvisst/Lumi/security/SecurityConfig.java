package com.jarvisst.Lumi.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 로그인 없이는 Lumi 화면/대화/일정 어느 것도 쓸 수 없게 전체 앱을 잠근다.
 * - 아이디/비밀번호 로그인(폼) + 구글 로그인(OAuth2) 둘 다 지원
 * - /login, /register, 회원가입 API, 구글 인증 콜백만 로그인 없이 허용
 * - /api/** 로 온 요청이 비로그인 상태면 로그인 페이지로 리다이렉트하지 않고 401만 내려준다
 *   (프론트가 fetch로 호출하므로, HTML 리다이렉트를 받으면 JSON 파싱이 깨지기 때문)
 *
 * CSRF: 쿠키 기반 더블 서브밋 패턴(CookieCsrfTokenRepository + 즉시 로딩하는
 * CsrfTokenRequestAttributeHandler)으로 켜둔다. 토큰을 세션이 아니라 JS가 읽을 수 있는
 * 쿠키(XSRF-TOKEN)에 저장하고, 모든 요청마다 즉시 꺼내 써서 쿠키가 항상 최신 상태로
 * 내려가게 한다 — React(5173)와 정적 화면(8081) 양쪽 다 이 쿠키 값을 그대로 읽어
 * X-XSRF-TOKEN 헤더로 되돌려보내면 통과된다. 전통적인 폼 POST(login.mustache)는
 * hidden input으로 같은 토큰을 실어 보낸다.
 *
 * React 개발 서버(5173)는 Vite 프록시를 거쳐 세션 쿠키를 공유하지만, 폼 로그인 자체가
 * "성공 시 302 리다이렉트"라 React의 fetch 기반 로그인엔 맞지 않는다. 그래서 React용으로
 * JSON 응답을 주는 `POST /api/auth/login`을 따로 두고(AuthController), 거기서 쓸
 * AuthenticationManager/SecurityContextRepository를 여기서 빈으로 노출한다.
 * 구글 로그인(OAuth2)은 8081/5173 중 어디서 시작했는지에 따라 돌아갈 주소가 달라야 해서
 * 커스텀 성공 핸들러(OAuth2ReturnToSuccessHandler)를 쓴다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final CustomOAuth2UserService oAuth2UserService;
    private final AuditLogoutHandler auditLogoutHandler;
    private final LogoutCleanupHandler logoutCleanupHandler;
    private final LoginRateLimitFilter loginRateLimitFilter;
    private final ApiRequestGuardFilter apiRequestGuardFilter;
    private final SessionGuardFilter sessionGuardFilter;
    private final WebAttackGuardFilter webAttackGuardFilter;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                           CustomOAuth2UserService oAuth2UserService,
                           AuditLogoutHandler auditLogoutHandler,
                           LogoutCleanupHandler logoutCleanupHandler,
                           LoginRateLimitFilter loginRateLimitFilter,
                           ApiRequestGuardFilter apiRequestGuardFilter,
                           SessionGuardFilter sessionGuardFilter,
                           WebAttackGuardFilter webAttackGuardFilter) {
        this.userDetailsService = userDetailsService;
        this.oAuth2UserService = oAuth2UserService;
        this.auditLogoutHandler = auditLogoutHandler;
        this.logoutCleanupHandler = logoutCleanupHandler;
        this.loginRateLimitFilter = loginRateLimitFilter;
        this.apiRequestGuardFilter = apiRequestGuardFilter;
        this.sessionGuardFilter = sessionGuardFilter;
        this.webAttackGuardFilter = webAttackGuardFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** React(5173)의 `POST /api/auth/login`에서 아이디/비번을 직접 검증하는 데 사용. */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** 컨트롤러에서 로그인 성공 후 SecurityContext를 세션에 직접 저장할 때 사용. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,
                                                   JwtAuthFilter jwtAuthFilter, JwtLogoutHandler jwtLogoutHandler,
                                                   JwtFormLoginSuccessHandler jwtFormLoginSuccessHandler,
                                                   JwtOAuth2SuccessHandler jwtOAuth2SuccessHandler) throws Exception {
        AuthenticationEntryPoint apiUnauthorizedEntryPoint =
                (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다.");

        // /api/** 여부 판단은 버전에 따라 이름이 바뀌는 매처 클래스(AntPathRequestMatcher 등) 대신
        // RequestMatcher를 람다로 직접 구현해 어떤 Spring Security 버전에서도 안전하게 컴파일되도록 한다.
        RequestMatcher apiRequestMatcher = request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return path.startsWith("/api/");
        };

        // 계정 관리: 연속 실패로 잠긴 계정은 "아이디/비번 오류"와 다른 안내를 보여준다.
        AuthenticationFailureHandler formLoginFailureHandler = (request, response, exception) -> {
            String target = (exception instanceof LockedException) ? "/login?locked" : "/login?error";
            response.sendRedirect(request.getContextPath() + target);
        };

        // 쿠키에 토큰을 저장하고(HttpOnly=false라 JS가 읽을 수 있음), 요청마다 "지연 로딩"
        // 없이 즉시 꺼내 쓴다(setCsrfRequestAttributeName(null)) — 그래야 매 응답마다 쿠키가
        // 갱신되어 내려가서, 정적 화면/React가 첫 GET 요청 직후부터 바로 이 쿠키를 읽을 수 있다.
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                )
                .headers(headers -> headers
                        // 스크립트는 같은 출처(three.js도 /js/vendor에 직접 호스팅)만 허용하고 Google Fonts(스타일/폰트)만 외부를 허용하며,
                        // 그 외 출처로의 삽입/연결은 막는다. 인라인 스크립트/스타일은 지금 구조상
                        // (index.html에 전부 인라인으로 작성됨) 당장 nonce로 바꾸기 어려워
                        // 'unsafe-inline'을 남겨두지만, frame-ancestors/object-src/base-uri 등
                        // 나머지 지시어는 엄격하게 막아 클릭재킹·외부 리소스 주입 경로를 줄인다.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                                        + "font-src 'self' https://fonts.gstatic.com; "
                                        + "img-src 'self' data: blob:; "
                                        + "media-src 'self' blob:; "
                                        + "connect-src 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "object-src 'none'"
                        ))
                        // 스니핑 대응: HTTPS로 한 번 접속한 브라우저는 1년 동안 http로 내려가지 않게 한다(SSL 스트리핑 방지).
                        // HTTPS 요청의 응답에만 실리므로 로컬 http 개발(8081/5173)에는 영향이 없다.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        // 검색 엔진에 색인되지 않도록(robots.txt와 함께) 응답마다 표시한다. 실제 접근 차단은 로그인이 담당한다.
                        .addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                )
                // 계정 단위 잠금(User.registerFailedLogin)과는 별개로, 같은 IP가 여러 아이디로
                // 분산 시도하는 크리덴셜 스터핑을 막기 위한 IP 단위 속도 제한. 실제 인증
                // (BCrypt 비교 등)보다 먼저 걸려야 하므로 UsernamePasswordAuthenticationFilter
                // 앞에 둔다.
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                // 웜/DoS성 과다 요청과 비정상적으로 큰 요청은 인증·CSRF 검사보다 앞에서 먼저 걸러낸다.
                // 주소·쿼리의 공격 시도(디렉터리 탐색, 스캐너, XSS/SQL 삽입 시험)는 가장 먼저 걸러 기록한다.
                .addFilterBefore(webAttackGuardFilter, CsrfFilter.class)
                .addFilterBefore(apiRequestGuardFilter, CsrfFilter.class)
                // 세션 하이재킹 대응: 인증 확인 직전에 세션의 기기 일치·유휴 시간·최대 수명을 검사한다.
                .addFilterBefore(sessionGuardFilter, AuthorizationFilter.class)
                // JWT 쿠키로 로그인 상태를 만든다(로그아웃 필터보다 앞이어야 로그아웃 때도 누구인지 안다).
                .addFilterBefore(jwtAuthFilter, LogoutFilter.class)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login", "/register", "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/oauth2/**", "/login/oauth2/**",
                                "/css/**", "/js/**", "/favicon.ico", "/robots.txt", "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(apiUnauthorizedEntryPoint, apiRequestMatcher)
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(jwtFormLoginSuccessHandler)
                        .failureHandler(formLoginFailureHandler)
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .successHandler(jwtOAuth2SuccessHandler)
                        .failureUrl("/login?error")
                        // 구글은 scope에 openid를 포함하는 OIDC 제공자라서, 일반 .userService(...)가
                        // 아니라 .oidcUserService(...)로 등록해야 CustomOAuth2UserService가 실제로 호출된다.
                        // (.userService(...)만 쓰면 이 핸들러는 전혀 호출되지 않고, 구글 ID 토큰 클레임만으로
                        // 만들어진 기본 사용자 객체가 그대로 쓰여서 DB에 사용자가 등록되지 않는다.)
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oAuth2UserService))
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler(auditLogoutHandler)
                        .addLogoutHandler(logoutCleanupHandler)
                        .addLogoutHandler(jwtLogoutHandler)
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        return http.build();
    }
}
