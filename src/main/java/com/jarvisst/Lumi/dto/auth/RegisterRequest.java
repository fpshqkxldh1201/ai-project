package com.jarvisst.Lumi.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        // 아이디는 화면 여러 곳(헤더, 로그 이력)에 그대로 표시되므로, 특수문자(< > " ' 등)를
        // 가입 단계에서부터 막는다(입력값 검증 — 저장형 XSS의 입구 차단).
        @NotBlank(message = "아이디를 입력해주세요.")
        @Size(min = 3, max = 30, message = "아이디는 3~30자여야 합니다.")
        @Pattern(regexp = "^[A-Za-z0-9가-힣_.\\-]+$",
                message = "아이디는 한글·영문·숫자와 _ . - 만 사용할 수 있습니다.")
        String username,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
        String password,

        @NotBlank(message = "비밀번호 확인을 입력해주세요.")
        @Size(max = 72, message = "비밀번호 확인이 너무 깁니다.")
        String confirmPassword
) {
}
