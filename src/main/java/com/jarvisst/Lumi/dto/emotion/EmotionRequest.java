package com.jarvisst.Lumi.dto.emotion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lumi 감정분석 서버(multilingual-e5-small/scripts/serve.py)로 보내는 요청.
 * type: "text" | "image" | "both". 사진은 이 컴퓨터 안의 서버로만 보내고 그 자리에서 버린다(base64 JPEG).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmotionRequest(
        String type,
        String text,
        @JsonProperty("image_base64") String imageBase64
) {
    public static EmotionRequest ofText(String text) {
        return new EmotionRequest("text", text, null);
    }

    public static EmotionRequest ofImageData(String base64Jpeg) {
        return new EmotionRequest("image", null, base64Jpeg);
    }

    /** 글과 사진을 한 번에 합쳐 분석하도록 보낸다. */
    public static EmotionRequest ofBoth(String text, String base64Jpeg) {
        return new EmotionRequest("both", text, base64Jpeg);
    }
}
