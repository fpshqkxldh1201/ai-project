package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.auth.AuditLogEntryResponse;
import com.jarvisst.Lumi.dto.auth.AuthMeResponse;
import com.jarvisst.Lumi.dto.auth.LoginRequest;
import com.jarvisst.Lumi.dto.auth.RegisterRequest;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.LoginAuditLogRepository;
import com.jarvisst.Lumi.repository.UserRepository;
import com.jarvisst.Lumi.security.PasswordPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * 로그인/회원가입 화면(Mustache) + 로그인 상태 조회·회원가입 API.
 * 정적 화면(8081)의 로그인(/login POST, /oauth2/authorization/google)은
 * SecurityConfig가 등록한 Spring Security 필터가 가로채서 처리하지만,
 * React(5173)는 fetch로 로그인하므로 302 리다이렉트 대신 JSON으로 답하는
 * POST /api/auth/login을 별도로 둔다.
 */
@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final LoginAuditLogRepository auditLogRepository;
    private final PasswordPolicy passwordPolicy;

    /** JWT 쿠키 발급(세션 대신). 필드 주입이라 생성자는 그대로 둔다. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.jarvisst.Lumi.security.JwtSessionService jwtSessions;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           SecurityContextRepository securityContextRepository,
                           LoginAuditLogRepository auditLogRepository,
                           PasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * CSRF가 꺼져 있던 때는 필요 없었지만, 다시 켠 뒤로는 /login이 전통적인 폼 POST라
     * hidden input으로 토큰을 같이 보내야 한다. CsrfFilter가 요청마다 request 속성
     * "_csrf"에 토큰을 넣어두므로, 그걸 그대로 모델에 담아 login.mustache에서 꺼내 쓴다.
     */
    @GetMapping("/login")
    public String loginPage(HttpServletRequest request, Model model) {
        model.addAttribute("_csrf", request.getAttribute("_csrf"));
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/api/auth/register")
    @ResponseBody
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "비밀번호가 일치하지 않습니다."));
        }
        // 취약한 패스워드(admin1234, 12345678, 아이디와 같은 값 등)는 서버에서 거부한다.
        String weak = passwordPolicy.validate(request.username(), request.password());
        if (weak != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", weak));
        }
        if (userRepository.existsByUsername(request.username())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "이미 사용 중인 아이디입니다."));
        }

        String hashed = passwordEncoder.encode(request.password());
        userRepository.save(User.localUser(request.username(), hashed));

        return ResponseEntity.ok(Map.of("message", "가입이 완료되었습니다. 로그인해주세요."));
    }

    /** React(5173)용 JSON 로그인. 성공 시 세션을 직접 만들어 저장하고 200을, 실패 시 401을 반환한다. */
    @PostMapping("/api/auth/login")
    @ResponseBody
    public ResponseEntity<Map<String, String>> apiLogin(@Valid @RequestBody LoginRequest request,
                                                          HttpServletRequest httpRequest,
                                                          HttpServletResponse httpResponse) {
        try {
            Authentication authRequest =
                    new UsernamePasswordAuthenticationToken(request.username(), request.password());
            Authentication authResult = authenticationManager.authenticate(authRequest);

            // 세션 고정(Session Fixation) 방지: 로그인 전에 공격자가 심어 둔 세션 ID를 그대로 쓰지 않고,
            // 인증에 성공한 순간 세션 ID를 새로 발급한다. (세션이 없으면 새로 만들어지므로 교체할 필요 없음)
            if (httpRequest.getSession(false) != null) {
                httpRequest.changeSessionId();
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authResult);
            SecurityContextHolder.setContext(context);
            // 세션 대신 JWT(HttpOnly 쿠키)로 로그인 상태를 만든다.
            jwtSessions.startLogin(authResult, httpRequest, httpResponse);

            return ResponseEntity.ok(Map.of("message", "로그인되었습니다."));
        } catch (LockedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "로그인 실패 횟수를 초과하여 계정이 15분간 잠겼습니다."));
        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "아이디 또는 비밀번호가 올바르지 않습니다."));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "로그인에 실패했습니다."));
        }
    }

    @GetMapping("/api/auth/me")
    @ResponseBody
    public AuthMeResponse me(Authentication authentication, @AuthenticationPrincipal Object principal) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return AuthMeResponse.anonymous();
        }

        if (principal instanceof OAuth2User oAuth2User) {
            String name = oAuth2User.getAttribute("name");
            String email = oAuth2User.getAttribute("email");
            String displayName = (name != null && !name.isBlank()) ? name : email;
            return new AuthMeResponse(true, displayName, "GOOGLE");
        }

        return new AuthMeResponse(true, authentication.getName(), "LOCAL");
    }

    /**
     * 로그 관리(AAA — 책임 추적성): 본인의 로그인 이력만 최근 20건 조회한다.
     * 다른 사용자의 로그는 username으로 필터링되어 절대 섞이지 않는다(권한 관리).
     */
    @GetMapping("/api/auth/audit")
    @ResponseBody
    public List<AuditLogEntryResponse> myAuditLog(Authentication authentication, @AuthenticationPrincipal Object principal) {
        String identity;
        if (principal instanceof OAuth2User oAuth2User) {
            String email = oAuth2User.getAttribute("email");
            identity = (email != null) ? email : authentication.getName();
        } else {
            identity = authentication.getName();
        }

        return auditLogRepository.findByUsernameOrderByOccurredAtDesc(identity).stream()
                .limit(20)
                .map(AuditLogEntryResponse::from)
                .toList();
    }
}
