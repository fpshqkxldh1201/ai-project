package com.jarvisst.Lumi.security;

import com.jarvisst.Lumi.entity.SecurityEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 웹 공격 시도 탐지·차단 (정보보안개론 4장 웹보안 — A7 공격 방어 취약점: "웹 애플리케이션 수준에서 자동으로
 * 탐지, 로깅, 응답 및 공격 시도 차단").
 *
 * 요청의 주소(경로)와 쿼리 문자열만 검사한다. 본문(JSON)은 대화 내용에 SQL·HTML 단어가 정상적으로 들어갈 수 있어
 * 여기서 막으면 오탐이 많으므로 검사하지 않는다(본문은 Bean Validation·프롬프트 인젝션 방어·출력 이스케이프가 담당).
 *
 * 탐지 대상
 *  - 디렉터리 탐색  : ../ , ..\ , /etc/passwd, win.ini  (슬라이드의 filename=../../etc/passwd)
 *  - 스캐너 흔적    : /.env, /.git, /phpmyadmin, /wp-admin 등 + .bak/.old/.sql/.php 같은 백업·서버 스크립트 요청
 *  - XSS 시험      : <script, javascript:, onerror= 등이 주소/쿼리에 포함
 *  - SQL 삽입 시험  : ' or 1=1, union select, information_schema, sleep( 등
 *  - 명령 삽입 시험 : ; cat /etc... 같은 셸 명령 조합
 *
 * 대응: 400으로 거절하고 보안 이벤트를 남긴다. 같은 IP가 10분 안에 N회(기본 5) 시도하면 그 IP를 M분(기본 10)
 * 차단한다. 한 학교/집 공유기(NAT)를 쓰는 다른 사용자까지 막을 수 있어 기본 차단 시간을 짧게 두었고,
 * security.web-attack.* 로 조정하거나 끌 수 있다.
 */
@Component
public class WebAttackGuardFilter extends OncePerRequestFilter {

    private static final long STRIKE_WINDOW_MS = 10 * 60_000L;
    private static final int MAX_TRACKED_IPS = 20_000;

    private static final Pattern PATH_TRAVERSAL = Pattern.compile("(\\.\\./|\\.\\.\\\\|/etc/passwd|/etc/shadow|win\\.ini|boot\\.ini)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCANNER_PATH = Pattern.compile(
            "(^|/)(\\.env|\\.git|\\.svn|\\.ds_store|\\.htaccess|wp-admin|wp-login\\.php|wp-content|phpmyadmin|xmlrpc\\.php|cgi-bin|web-inf|meta-inf|actuator|h2-console)(/|$)"
                    + "|\\.(bak|old|orig|swp|tmp|sql|log|ini|properties|yml|yaml|conf|pem|key|php|asp|aspx|jsp)$|~$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XSS_PROBE = Pattern.compile("(<\\s*/?\\s*(script|img|svg|iframe|object|embed|body)\\b|javascript:|vbscript:|\\bon(error|load|click|mouseover|focus)\\s*=)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQLI_PROBE = Pattern.compile(
            "('\\s*(or|and)\\s*['\\d(]|\\b(or|and)\\s+\\d+\\s*=\\s*\\d+|union\\s+(all\\s+)?select|information_schema|;\\s*(drop|delete|insert|update|truncate)\\s|\\bsleep\\s*\\(|\\bbenchmark\\s*\\(|/\\*.*\\*/|'\\s*(--|#))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_PROBE = Pattern.compile("(;|\\|\\||&&|\\||`|\\$\\()\\s*(cat|ls|wget|curl|nc|netcat|bash|sh|powershell|cmd|whoami|id)\\b", Pattern.CASE_INSENSITIVE);

    private final boolean enabled;
    private final int maxStrikes;
    private final long banMs;
    private final ClientIpResolver clientIpResolver;
    private final SecurityEventService securityEvents;

    private final Map<String, Deque<Long>> strikes = new ConcurrentHashMap<>();
    private final Map<String, Long> bannedUntil = new ConcurrentHashMap<>();

    public WebAttackGuardFilter(@Value("${security.web-attack.enabled:true}") boolean enabled,
                                @Value("${security.web-attack.strikes:5}") int maxStrikes,
                                @Value("${security.web-attack.ban-minutes:10}") long banMinutes,
                                ClientIpResolver clientIpResolver, SecurityEventService securityEvents) {
        this.enabled = enabled;
        this.maxStrikes = maxStrikes;
        this.banMs = banMinutes * 60_000L;
        this.clientIpResolver = clientIpResolver;
        this.securityEvents = securityEvents;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        String ipKey = ip == null ? "unknown" : ip;
        long now = System.currentTimeMillis();

        Long until = bannedUntil.get(ipKey);
        if (until != null) {
            if (until > now) {
                reject(response, 403, "비정상적인 요청이 반복되어 잠시 접속이 제한되었습니다.");
                return;
            }
            bannedUntil.remove(ipKey, until);
        }

        String kind = detect(request);
        if (kind == null) {
            chain.doFilter(request, response);
            return;
        }

        securityEvents.record(null, SecurityEvent.Type.WEB_ATTACK_SUSPECTED,
                kind + " — " + request.getMethod() + " " + abbreviate(request.getRequestURI()), ip);
        if (registerStrike(ipKey, now)) {
            bannedUntil.put(ipKey, now + banMs);
            securityEvents.record(null, SecurityEvent.Type.IP_BLOCKED,
                    "공격 시도 " + maxStrikes + "회 반복 — " + (banMs / 60_000L) + "분간 차단", ip);
        }
        reject(response, 400, "잘못된 요청입니다.");
    }

    /** 의심 유형을 설명하는 문구를, 정상이면 null을 돌려준다. (패키지 공개: 단위 테스트용) */
    String detect(HttpServletRequest request) {
        String path = decode(request.getRequestURI());
        // 구글 로그인 콜백(/login/oauth2/...)의 쿼리(code, state)는 구글이 만든 임의 문자열이라 오탐 가능성이 있어
        // 쿼리는 검사하지 않고 경로만 본다. 이 값들은 Spring Security가 state 일치 여부로 따로 검증한다.
        boolean oauthCallback = path.startsWith("/login/oauth2/") || path.startsWith("/oauth2/");
        String query = (request.getQueryString() == null || oauthCallback) ? "" : decode(request.getQueryString());
        String both = path + "?" + query;

        if (PATH_TRAVERSAL.matcher(both).find()) {
            return "디렉터리 탐색 시도";
        }
        if (SCANNER_PATH.matcher(path).find()) {
            return "스캐너·백업 파일 접근 시도";
        }
        if (XSS_PROBE.matcher(both).find()) {
            return "XSS 시도";
        }
        if (SQLI_PROBE.matcher(query).find() || SQLI_PROBE.matcher(path).find()) {
            return "SQL 삽입 시도";
        }
        if (CMD_PROBE.matcher(both).find()) {
            return "명령 삽입 시도";
        }
        return null;
    }

    private boolean registerStrike(String key, long now) {
        if (strikes.size() > MAX_TRACKED_IPS) {
            strikes.clear();
        }
        Deque<Long> deque = strikes.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > STRIKE_WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            if (deque.size() >= maxStrikes) {
                deque.clear();
                return true;
            }
            return false;
        }
    }

    /** 인코딩으로 숨긴 공격(%2e%2e%2f, %3Cscript%3E)을 잡기 위해 두 번 디코딩한다. */
    private static String decode(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        for (int i = 0; i < 2; i++) {
            try {
                s = URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                break; // 깨진 %인코딩은 그대로 검사
            }
        }
        return s;
    }

    private static String abbreviate(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) : s;
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
