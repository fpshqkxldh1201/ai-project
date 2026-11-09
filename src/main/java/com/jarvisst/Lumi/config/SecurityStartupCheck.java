package com.jarvisst.Lumi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 기동할 때 한 번, 서버의 보안 설정을 점검해서 콘솔에 요약한다.
 *
 * 정보보안개론 6장(악성 코드 탐지 및 대응)의 "정상 상태와 비교하기"를 서버 설정에 적용한 것이다.
 * 악성 코드를 찾을 때 netstat으로 열려 있는 포트를 보고(어디서 대기 중인가), 정상 프로세스 목록과 비교하고(남아 있으면
 * 안 되는 것이 없는가), 시작 프로그램·레지스트리를 확인하듯이 —
 *   · 어느 주소/포트에서 접속을 받는가            (열린 포트 점검)
 *   · 백도어가 될 수 있는 개발용 기능이 켜져 있지 않은가 (actuator 전체 노출, H2 콘솔, 스택트레이스 노출)
 *   · 운영에 부적절한 설정이 남아 있지 않은가       (ddl-auto create, SQL 로그, 보안 로그 DEBUG)
 *   · 필수 비밀값이 비어 있지 않은가              (DB 비밀번호, OAuth 시크릿)
 *   · 외부 통신이 HTTPS·공식 도메인인가, 느린 요청 방어 설정이 있는가 (3장 네트워크 보안)
 * 를 기준값과 비교해 [정상]/[주의]로 알려 준다. 점검만 하고 설정을 바꾸지는 않는다.
 */
@Component
public class SecurityStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);

    private final Environment env;

    public SecurityStartupCheck(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        List<String> ok = new ArrayList<>();
        List<String> warn = new ArrayList<>();

        // 1) 열린 포트 점검 (netstat으로 대기 중인 포트 확인하듯이)
        String port = env.getProperty("server.port", "8080");
        String address = env.getProperty("server.address", "");
        if (address.isBlank()) {
            warn.add("접속 대기 주소가 지정되지 않아 모든 네트워크 인터페이스(0.0.0.0:" + port + ")에서 접속을 받습니다. "
                    + "로컬 개발이면 괜찮지만, 배포 시에는 리버스 프록시 뒤에서 server.address=127.0.0.1로 두는 것을 권장합니다.");
        } else {
            ok.add("접속 대기 주소: " + address + ":" + port);
        }

        // 2) 백도어가 될 수 있는 개발용 기능 점검
        String exposure = env.getProperty("management.endpoints.web.exposure.include", "");
        if (exposure.contains("*")) {
            warn.add("actuator 엔드포인트가 전부 노출되어 있습니다(management.endpoints.web.exposure.include=*). "
                    + "환경변수·힙덤프 등이 외부에 보일 수 있어 health만 노출하세요.");
        } else {
            ok.add("actuator 전체 노출 없음");
        }
        if ("true".equalsIgnoreCase(env.getProperty("spring.h2.console.enabled", "false"))) {
            warn.add("H2 웹 콘솔이 켜져 있습니다(spring.h2.console.enabled=true). 데이터베이스로 바로 들어가는 문이 됩니다.");
        } else {
            ok.add("H2 웹 콘솔 꺼짐");
        }
        String stacktrace = env.getProperty("server.error.include-stacktrace", "never").toLowerCase(Locale.ROOT);
        String errMessage = env.getProperty("server.error.include-message", "never").toLowerCase(Locale.ROOT);
        if ("always".equals(stacktrace) || "on_param".equals(stacktrace) || "always".equals(errMessage)) {
            warn.add("오류 응답에 스택트레이스/내부 메시지가 포함되도록 설정되어 있습니다(server.error.include-*). "
                    + "내부 구조가 공격자에게 노출됩니다.");
        } else {
            ok.add("오류 응답에 내부 정보 미포함");
        }

        // 3) 운영에 부적절한 설정이 남아 있는지
        String ddl = env.getProperty("spring.jpa.hibernate.ddl-auto", "none").toLowerCase(Locale.ROOT);
        if ("create".equals(ddl) || "create-drop".equals(ddl)) {
            warn.add("ddl-auto=" + ddl + " : 앱을 켤 때마다 테이블을 지웁니다. 데이터가 사라질 수 있습니다.");
        } else if ("update".equals(ddl)) {
            warn.add("ddl-auto=update : 개발 편의용입니다. 배포 시에는 validate 또는 none을 권장합니다.");
        } else {
            ok.add("ddl-auto=" + ddl);
        }
        if ("true".equalsIgnoreCase(env.getProperty("spring.jpa.show-sql", "false"))) {
            warn.add("SQL 로그 출력이 켜져 있습니다(spring.jpa.show-sql=true). 개인 정보가 로그에 남을 수 있습니다.");
        }
        String securityLog = env.getProperty("logging.level.org.springframework.security", "").toUpperCase(Locale.ROOT);
        if ("DEBUG".equals(securityLog) || "TRACE".equals(securityLog)) {
            warn.add("Spring Security 로그가 " + securityLog + " 입니다. 세션·인증 정보가 로그에 남을 수 있으니 운영에서는 낮추세요.");
        }

        // 4) 세션 쿠키 속성
        if (!"true".equalsIgnoreCase(env.getProperty("server.servlet.session.cookie.secure", "false"))) {
            warn.add("세션 쿠키에 Secure 속성이 꺼져 있습니다. 로컬 HTTP 개발에서는 정상이지만, HTTPS로 배포할 때는 "
                    + "server.servlet.session.cookie.secure=true 로 켜야 합니다.");
        } else {
            ok.add("세션 쿠키 Secure 켜짐");
        }
        if ("false".equalsIgnoreCase(env.getProperty("server.servlet.session.cookie.http-only", "true"))) {
            warn.add("세션 쿠키 HttpOnly가 꺼져 있습니다. XSS가 발생하면 세션이 탈취될 수 있습니다.");
        } else {
            ok.add("세션 쿠키 HttpOnly 켜짐");
        }
        if ("true".equalsIgnoreCase(env.getProperty("security.trusted-proxy.enabled", "false"))) {
            warn.add("X-Forwarded-For를 신뢰하도록 설정되어 있습니다. 신뢰할 수 있는 프록시 뒤가 아니라면 IP 위조가 가능합니다.");
        }

        // 4-1) 개발 도구가 운영에 섞여 들어가지 않았는지 (A5 보안 설정 오류)
        if (isClassPresent("org.springframework.boot.devtools.restart.Restarter")) {
            warn.add("spring-boot-devtools가 실행 중입니다. 개발 편의 도구라 배포 빌드에는 포함하지 않는 것이 안전합니다"
                    + "(build.gradle에서 developmentOnly로 선언되어 있으면 배포용 jar에서는 자동으로 빠집니다).");
        } else {
            ok.add("개발 도구(devtools) 미포함");
        }
        String sameSite = env.getProperty("server.servlet.session.cookie.same-site", "").toLowerCase(Locale.ROOT);
        if ("none".equals(sameSite)) {
            warn.add("세션 쿠키 SameSite=None 입니다. 다른 사이트에서 오는 요청에도 쿠키가 실려 CSRF 위험이 커집니다.");
        } else if (sameSite.isBlank()) {
            warn.add("세션 쿠키 SameSite가 지정되지 않았습니다(브라우저 기본값에 의존). server.servlet.session.cookie.same-site=lax 로 명시하세요.");
        } else {
            ok.add("세션 쿠키 SameSite=" + sameSite);
        }

        // 4-2) 최소 권한 (5장: SetUID — 파일이 소유자 권한으로 실행되므로 취약점이 생기면 그 권한을 그대로 빼앗긴다)
        //      앱이 DB 관리자(root)로 붙어 있으면 SQL 삽입이나 코드 결함 한 번에 DB 전체·서버 설정까지 위험해진다.
        String dbUser = env.getProperty("spring.datasource.username", "");
        if ("root".equalsIgnoreCase(dbUser) || "admin".equalsIgnoreCase(dbUser) || "sa".equalsIgnoreCase(dbUser)) {
            warn.add("앱이 DB 관리자 계정(" + dbUser + ")으로 접속합니다. lumi_database 한 곳에만 권한이 있는 전용 계정을 만들어 쓰세요(최소 권한 원칙).");
        } else if (!dbUser.isBlank()) {
            ok.add("DB 접속 계정: 전용 계정(" + dbUser + ")");
        }
        String osUser = System.getProperty("user.name", "");
        if ("root".equalsIgnoreCase(osUser) || "administrator".equalsIgnoreCase(osUser)) {
            warn.add("서버가 관리자 계정(" + osUser + ")으로 실행 중입니다. 일반 사용자 계정으로 실행하세요.");
        }

        // 4-3) 비밀값이 설정 파일에 평문으로 적혀 있는지 (5장 메모리 해킹: 중요한 값은 평문으로 두지 않는다)
        List<String> literalSecrets = findLiteralSecrets();
        if (literalSecrets.isEmpty()) {
            ok.add("비밀값이 설정 파일에 평문으로 적혀 있지 않음");
        } else {
            warn.add("비밀값이 설정 파일(application.properties)에 직접 적혀 있습니다: " + String.join(", ", literalSecrets)
                    + " — 환경변수로 옮기세요. 파일이 저장소·압축본·화면 공유로 새면 그대로 노출됩니다.");
        }

        // 5) 외부 통신 대상 점검 — 스푸핑(가짜 서버로 유도) 대응
        //    DNS/ARP 스푸핑으로 엉뚱한 서버에 연결되더라도 평문으로 비밀값(API 키)을 보내지 않도록,
        //    외부 API 주소는 https여야 하고 알려진 공식 도메인이어야 한다. (인증서 검증은 JDK 기본값을 그대로 사용)
        checkOutbound("gemini.api.base-url", "googleapis.com", ok, warn);
        String emotionUrl = env.getProperty("emotion.server.base-url", "http://localhost:8002");
        if (!emotionUrl.isBlank()) {
            String host = hostOf(emotionUrl);
            if (isLoopback(host)) {
                ok.add("감정 분석 서버는 이 컴퓨터 안(" + host + ")에서만 통신");
            } else if (emotionUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                ok.add("감정 분석 서버: HTTPS (" + host + ")");
            } else {
                warn.add("감정 분석 서버(" + host + ")와 암호화 없이(http) 통신합니다. 카메라 분석 결과가 네트워크에서 "
                        + "엿보일(스니핑) 수 있으니 같은 컴퓨터에서 실행하거나 HTTPS로 바꾸세요.");
            }
        }

        // 6) 느린 요청·연결 고갈형 DoS(Slowloris, Slow POST) 방어 설정
        if (env.getProperty("server.tomcat.connection-timeout", "").isBlank()) {
            warn.add("server.tomcat.connection-timeout 이 지정되지 않았습니다(기본 20초). 요청을 아주 천천히 보내 연결을 붙잡는 "
                    + "Slowloris·Slow POST에 대비해 10s 정도로 명시하는 것을 권장합니다.");
        } else {
            ok.add("톰캣 연결 타임아웃 지정: " + env.getProperty("server.tomcat.connection-timeout"));
        }

        // 7) 필수 비밀값이 비어 있지 않은지(값 자체는 출력하지 않는다)
        if (isBlank("spring.datasource.password")) {
            warn.add("데이터베이스 비밀번호가 비어 있습니다(DB_PASSWORD 환경변수 확인).");
        } else {
            ok.add("데이터베이스 비밀번호 설정됨");
        }
        if (isBlank("spring.security.oauth2.client.registration.google.client-secret")) {
            warn.add("구글 로그인 client-secret이 비어 있습니다. 구글 로그인은 동작하지 않습니다.");
        }

        log.info("========================================================");
        log.info(" LUMI 보안 설정 점검 (정상 {}건 / 주의 {}건)", ok.size(), warn.size());
        log.info("--------------------------------------------------------");
        ok.forEach(m -> log.info(" [정상] {}", m));
        warn.forEach(m -> log.warn(" [주의] {}", m));
        log.info("========================================================");
    }

    private void checkOutbound(String key, String allowedDomain, List<String> ok, List<String> warn) {
        String url = env.getProperty(key, "");
        if (url.isBlank()) {
            return;
        }
        String host = hostOf(url);
        boolean https = url.toLowerCase(Locale.ROOT).startsWith("https://");
        boolean known = host != null && (host.equals(allowedDomain) || host.endsWith("." + allowedDomain));
        if (!https) {
            warn.add(key + " 가 https가 아닙니다. API 키가 평문으로 전송됩니다.");
        } else if (!known) {
            warn.add(key + " 의 도메인(" + host + ")이 예상한 공식 도메인(" + allowedDomain + ")이 아닙니다. 주소가 변조되지 않았는지 확인하세요.");
        } else {
            ok.add(key + ": HTTPS + 공식 도메인(" + host + ")");
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    /** 비밀값 키 중, 값이 환경변수(${...})가 아니라 설정 파일에 그대로 적힌 것의 키 이름(값은 출력하지 않음). */
    private List<String> findLiteralSecrets() {
        List<String> literal = new ArrayList<>();
        if (!(env instanceof ConfigurableEnvironment configurable)) {
            return literal;
        }
        String[] keys = {
                "spring.datasource.password",
                "spring.security.oauth2.client.registration.google.client-secret",
                "gemini.api.key", "naver.api.client-secret", "fish.audio.api-key", "youtube.api.key"
        };
        for (String key : keys) {
            String resolved = env.getProperty(key);
            if (resolved == null || resolved.isBlank()) {
                continue;
            }
            // 우선순위 순서로 훑어 이 키를 처음 가진 출처가 설정 파일이고, 값에 ${...} 치환이 없으면 평문이다.
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source.containsProperty(key)) {
                    Object raw = source.getProperty(key);
                    boolean fromFile = source.getName() != null && source.getName().contains("Config resource");
                    if (fromFile && raw != null && !raw.toString().contains("${")) {
                        literal.add(key);
                    }
                    break;
                }
            }
        }
        return literal;
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, SecurityStartupCheck.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private boolean isBlank(String key) {
        String value = env.getProperty(key);
        return value == null || value.isBlank();
    }
}
