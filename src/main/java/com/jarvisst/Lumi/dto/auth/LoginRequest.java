package com.jarvisst.Lumi.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** React(5173) 프론트가 fetch로 로그인할 때 쓰는 JSON 로그인 요청(POST /api/auth/login). */
public record LoginRequest(
        @NotBlank(message = "아이디를 입력해주세요.")
        @Size(max = 100, message = "아이디가 너무 깁니다.")
        String username,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(max = 128, message = "비밀번호가 너무 깁니다.")
        String password
) {
}
