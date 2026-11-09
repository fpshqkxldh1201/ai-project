package com.jarvisst.Lumi.dto.tts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TtsRequest(
        @NotBlank(message = "읽을 텍스트가 없습니다.")
        @Size(max = 2000, message = "읽을 텍스트가 너무 깁니다.")
        String text,

        @Pattern(regexp = "ko|en|zh|ja|es", message = "지원하지 않는 언어입니다.")
        String language,

        @Pattern(regexp = "male|female", message = "음성 성별 값이 올바르지 않습니다.")
        String gender
) {
}
