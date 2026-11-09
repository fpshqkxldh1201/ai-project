package com.jarvisst.Lumi.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT(HS256) 발급·검증. 외부 라이브러리 없이 JDK(HmacSHA256)만 쓴다.
 *
 * 보안 규칙(정보보안개론 — 인증·세션·암호):
 *  - 서명 비밀키는 환경변수 JWT_SECRET(32자 이상)로만 받는다. 코드·properties·저장소에 넣지 않는다.
 *    없거나 짧으면 서버를 켤 때마다 새 무작위 키를 만들어 쓴다(안전하지만 재시작하면 모두 다시 로그인).
 *  - 검증은 헤더가 정확히 {"alg":"HS256","typ":"JWT"} 일 때만 통과한다(alg=none, 다른 알고리즘 바꿔치기 차단).
 *  - 서명 비교는 시간 일정(MessageDigest.isEqual), 만료(exp)·발급자(iss)·용도(typ: at/rt)를 모두 확인한다.
 */
@Component
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String ISSUER = "lumi";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final String configuredSecret;
    private final ObjectMapper mapper = new ObjectMapper();
    private byte[] key;

    public JwtTokenService(@Value("${security.jwt.secret:${JWT_SECRET:}}") String configuredSecret) {
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret.trim();
    }

    @PostConstruct
    void init() {
        if (configuredSecret.length() >= 32) {
            key = configuredSecret.getBytes(StandardCharsets.UTF_8);
            log.info("[JWT] 서명 키: 환경변수 JWT_SECRET 사용");
        } else {
            key = new byte[48];
            new SecureRandom().nextBytes(key);
            log.warn("[JWT] JWT_SECRET 이 없거나 32자 미만이라 이번 실행 전용 무작위 키를 씁니다 — 서버를 재시작하면 모두 다시 로그인해야 해요. "
                    + "운영에서는 32자 이상 JWT_SECRET 환경변수를 설정하세요.");
        }
    }

    /** 검증을 통과한 토큰의 내용. */
    public record Claims(String type, String subject, String jti, String sid, String fp, long iat, long exp, long start) {
    }

    /** type: "at"(접근) 또는 "rt"(재발급). start 는 로그인 시작 시각(초) — 최대 수명 계산용. */
    public String issue(String type, String subject, String jti, String sid, String fp, long iatSec, long expSec, long startSec) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("iss", ISSUER);
        p.put("typ", type);
        p.put("sub", subject);
        p.put("jti", jti);
        p.put("sid", sid);
        p.put("fp", fp);
        p.put("iat", iatSec);
        p.put("exp", expSec);
        p.put("st", startSec);
        String signingInput = B64.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(mapper.writeValueAsString(p).getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + B64.encodeToString(hmac(signingInput));
    }

    /** 서명·만료·발급자·용도가 모두 맞으면 내용을, 하나라도 틀리면 null. 예외를 밖으로 던지지 않는다. */
    public Claims parse(String token, String expectedType, long nowSec) {
        return parse(token, expectedType, nowSec, false);
    }

    /** allowExpired=true 는 로그아웃처럼 "만료됐어도 누구 것인지만 알면 되는" 경우에만 쓴다(서명은 그대로 검증). */
    public Claims parse(String token, String expectedType, long nowSec, boolean allowExpired) {
        try {
            if (token == null || token.length() > 2048) {
                return null;
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return null;
            }
            String header = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
            if (!HEADER_JSON.equals(header)) {
                return null;
            }
            byte[] given = B64D.decode(parts[2]);
            if (!MessageDigest.isEqual(given, hmac(parts[0] + "." + parts[1]))) {
                return null;
            }
            Map<String, Object> p = mapper.readValue(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() { });
            if (!ISSUER.equals(p.get("iss")) || !expectedType.equals(p.get("typ"))) {
                return null;
            }
            long exp = num(p.get("exp"));
            long iat = num(p.get("iat"));
            if ((!allowExpired && exp <= nowSec) || iat > nowSec + 60) {
                return null;
            }
            String sub = str(p.get("sub")), jti = str(p.get("jti")), sid = str(p.get("sid"));
            if (sub == null || jti == null || sid == null) {
                return null;
            }
            return new Claims(expectedType, sub, jti, sid, str(p.get("fp")), iat, exp, num(p.get("st")));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }
}
