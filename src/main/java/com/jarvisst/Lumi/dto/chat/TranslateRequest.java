package com.jarvisst.Lumi.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 방금 받은 Lumi 답변의 한국어 번역 요청. 서버가 기억하고 있는 최근 답변과 같은 글만 번역해 준다. */
public record TranslateRequest(@NotBlank @Size(max = 2000) String text) {
}
