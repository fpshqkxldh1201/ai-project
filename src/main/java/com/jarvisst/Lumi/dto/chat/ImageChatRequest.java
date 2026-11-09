package com.jarvisst.Lumi.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 이미지가 붙은 대화 요청. 이미지는 base64 문자열로 받는다(임시 파일이 디스크에 남지 않도록 멀티파트를 쓰지 않는다).
 * message는 비어 있어도 된다(이미지만 보내는 경우).
 */
public record ImageChatRequest(
        @Size(max = 2000, message = "메시지는 2000자 이하로 입력해주세요.")
        String message,

        @Pattern(regexp = "ko|en|zh|ja|es", message = "지원하지 않는 답변 언어입니다.")
        String responseLanguage,

        @NotBlank(message = "이미지가 비어 있습니다.")
        @Size(max = 4_200_000, message = "이미지가 너무 큽니다. 3MB 이하로 올려주세요.")
        String imageBase64,

        @Pattern(regexp = "image/(jpeg|png|gif)", message = "지원하지 않는 이미지 형식입니다. JPG, PNG, GIF만 올릴 수 있습니다.")
        String imageMime
) {
}
