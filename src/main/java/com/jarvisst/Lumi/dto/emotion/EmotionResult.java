package com.jarvisst.Lumi.dto.emotion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lumi 감정분석 서버의 응답.
 *
 * primaryEmotion/secondaryEmotion: 정서 이름(예: "슬픔", "안타까움/실망"), 감정이 약하면 "중립"
 * mood: 답변 톤 정책(EmotionTonePolicy)이 쓰는 기분 이름(happy, energetic, calm, sad, angry, melancholy, neutral ...)
 * valence/arousal: 기분의 좋고 나쁨 / 에너지의 높고 낮음 (-1 ~ 1)
 * confidence: 0 ~ 1
 * ambiguous: 글만으로는 감정을 판단하기 어렵다는 신호(true면 화면이 사진을 청할 수 있다). 없으면 false
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmotionResult(
        @JsonProperty("primary_emotion") String primaryEmotion,
        @JsonProperty("secondary_emotion") String secondaryEmotion,
        String mood,
        double valence,
        double arousal,
        double confidence,
        @JsonProperty("model_version") String modelVersion,
        boolean ambiguous
) {
}
