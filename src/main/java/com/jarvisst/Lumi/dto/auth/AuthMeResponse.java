package com.jarvisst.Lumi.dto.auth;

/** 로그인된 사용자 정보를 프론트엔드 헤더에 표시하기 위한 응답(/api/auth/me). */
public record AuthMeResponse(
        boolean authenticated,
        String displayName,
        String provider
) {
    public static AuthMeResponse anonymous() {
        return new AuthMeResponse(false, null, null);
    }
}
