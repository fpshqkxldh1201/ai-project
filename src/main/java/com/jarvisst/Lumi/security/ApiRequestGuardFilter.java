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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 웜·네트워크 공격형 웜(정보보안개론 6장: 과다 트래픽, 서비스 거부)에 대한 입구 방어.
 * 로그인/대화처럼 기능별 제한과는 별개로, /api/** 전체에 대해 IP별로 다음 두 가지를 먼저 걸러낸다.
 *
 *  1) 요청 속도: IP당 1분에 N회(기본 300회)를 넘으면 429 — 인증·CSRF 검사보다 앞에서 끝내서
 *     과다 요청이 서버 자원(세션 조회, DB 조회, 외부 API 호출)을 더 쓰지 못하게 한다.
 *  2) 요청 크기: 본문이 64KB를 넘으면 413 — 이 앱의 정상 요청(대화 2000자 등)은 훨씬 작다.
 *     단, 이미지가 붙는 /api/chat/image 한 곳만 5MB까지 허용한다.
 *
 * 한 학교/집 공유기(NAT) 뒤의 여러 사용자가 같은 IP를 쓸 수 있어 기본값을 넉넉하게 잡았고,
 * security.api-rate-limit.per-minute 로 조정할 수 있다.
 */
@Component
public class ApiRequestGuardFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final long MAX_BODY_BYTES = 64 * 1024;
    /** 이미지가 붙는 대화 요청만 예외로 더 크게 받는다(원본 3MB를 base64로 보내면 약 4MB). */
    private static final String IMAGE_CHAT_PATH = "/api/chat/image";
    private static final long MAX_IMAGE_BODY_BYTES = 5L * 1024 * 1024;
    /** 서버 음성 인식(STT): 짧은 음성 파일 업로드만 예외로 1MB까지 받는다. */
    private static final String STT_PATH = "/api/stt";
    private static final long MAX_STT_BODY_BYTES = 1024L * 1024;
    /**
     * 그림을 base64 JSON으로 보내는 분석 요청(화면 분석·사진 감정·대화 캡처)도 예외로 3MB까지 받는다.
     * (서비스 쪽 상한은 base64 약 2.2MB — 64KB 기본 한도로는 스크린샷 한 장도 통과하지 못한다.)
     */
    private static final Set<String> IMAGE_JSON_PATHS = Set.of("/api/vision/screen", "/api/emotion/deep", "/api/intent/other/screen");
    private static final long MAX_IMAGE_JSON_BODY_BYTES = 3L * 1024 * 1024;
    private static final int MAX_TRACKED_IPS = 20_000;

    private final int perMinute;
    private final ClientIpResolver clientIpResolver;
    private final SecurityEventService securityEvents;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public ApiRequestGuardFilter(@Value("${security.api-rate-limit.per-minute:300}") int perMinute,
                                 ClientIpResolver clientIpResolver, SecurityEventService securityEvents) {
        this.perMinute = perMinute;
        this.clientIpResolver = clientIpResolver;
        this.securityEvents = securityEvents;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(request.getContextPath() + "/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);

        final String relPath = path.substring(request.getContextPath().length());
        final long maxBody = path.equals(request.getContextPath() + IMAGE_CHAT_PATH) ? MAX_IMAGE_BODY_BYTES
                : path.equals(request.getContextPath() + STT_PATH) ? MAX_STT_BODY_BYTES
                : IMAGE_JSON_PATHS.contains(relPath) ? MAX_IMAGE_JSON_BODY_BYTES : MAX_BODY_BYTES;
        if (request.getContentLengthLong() > maxBody) {
            securityEvents.record(null, SecurityEvent.Type.OVERSIZED_REQUEST,
                    "요청 본문 " + request.getContentLengthLong() + "바이트 (" + path + ")", ip);
            reject(response, 413, "요청이 너무 큽니다.");
            return;
        }

        if (!tryAcquire(ip == null ? "unknown" : ip)) {
            securityEvents.record(null, SecurityEvent.Type.API_RATE_LIMIT,
                    "IP당 분당 " + perMinute + "회 초과 (" + path + ")", ip);
            reject(response, 429, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
            return;
        }

        // Content-Length 없이(chunked) 큰 본문을 밀어 넣는 우회를 막기 위해, 헤더가 없거나 거짓일 때는
        // 실제로 읽은 바이트 수로도 한도를 검사한다. 한도를 넘으면 읽기가 중단되고 요청은 오류로 끝난다.
        HttpServletRequest limited = request;
        if (request.getContentLengthLong() < 0 && hasBody(request)) {
            limited = new BodyLimitRequestWrapper(request, maxBody, () ->
                    securityEvents.record(null, SecurityEvent.Type.OVERSIZED_REQUEST,
                            "본문 길이 헤더 없이 " + maxBody + "바이트 초과 전송 (" + path + ")", ip));
        }

        chain.doFilter(limited, response);
    }

    private boolean hasBody(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private boolean tryAcquire(String key) {
        if (hits.size() > MAX_TRACKED_IPS) {
            hits.clear();
        }
        long now = System.currentTimeMillis();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            if (deque.size() >= perMinute) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        // 서블릿 스펙에는 429/413용 SC_* 상수가 없거나 이름이 달라서 숫자를 직접 쓴다.
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
