package com.jarvisst.Lumi.dto.settings;

import jakarta.validation.constraints.Pattern;

/**
 * 필드를 전부 null-허용으로 둬서, 바뀐 값만 보내는 부분 업데이트도 가능하게 한다
 * (프론트는 현재는 항상 다 같이 보내지만, 추후 하나만 바뀌어도 동작하도록).
 * 값이 있을 때는 허용 목록에 있는 것만 받는다(임의 문자열이 DB에 저장되거나 나중에
 * 프롬프트·TTS 호출로 흘러가는 것을 막기 위함).
 */
public record SettingsRequest(
        @Pattern(regexp = "ko|en|zh|ja|es", message = "지원하지 않는 입력 언어입니다.")
        String inputLang,

        @Pattern(regexp = "auto|ko|en|zh|ja|es", message = "지원하지 않는 답변 언어입니다.")
        String responseLang,

        @Pattern(regexp = "male|female", message = "음성 성별 값이 올바르지 않습니다.")
        String voiceGender,

        Boolean ttsEnabled,
        Boolean alwaysListening,
        Boolean emotionLogEnabled,
        Boolean proactiveCareEnabled
) {
}
