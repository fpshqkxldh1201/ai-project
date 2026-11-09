package com.jarvisst.Lumi.dto.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;

// 응답 속도를 위한 설정. maxOutputTokens로 혹시 모를 과도하게 긴 응답의 최악의 경우
// 생성 시간을 상한선으로 묶어두고, thinkingConfig로 "생각(thinking)" 토큰 생성을
// 최소화해서(혹은 끈 상태로) 실제 답변을 쓰기 전에 걸리는 시간을 줄인다. 페르소나
// 시스템 프롬프트가 이미 "장황하지 않게 핵심만" 답하도록 지시하므로, 긴 사고 과정이
// 답변 품질에 크게 기여하지 않는 대화형 비서 용도에는 큰 손해 없이 적용할 수 있다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiGenerationConfig(Integer maxOutputTokens, GeminiThinkingConfig thinkingConfig) {
}
