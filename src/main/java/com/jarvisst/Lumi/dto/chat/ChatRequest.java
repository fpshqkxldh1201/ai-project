package com.jarvisst.Lumi.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "메시지를 입력해주세요.")
        @Size(max = 2000, message = "메시지는 2000자 이하로 입력해주세요.")
        String message,

        // 이 값은 Gemini 프롬프트에 들어가므로 허용 목록(화이트리스트)에 있는 언어 코드만 받는다.
        // null이면 "사용자 언어 자동".
        @Pattern(regexp = "ko|en|zh|ja|es", message = "지원하지 않는 답변 언어입니다.")
        String responseLanguage
) {
}
