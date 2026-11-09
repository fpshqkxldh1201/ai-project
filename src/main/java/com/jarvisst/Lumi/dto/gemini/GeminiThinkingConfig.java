package com.jarvisst.Lumi.dto.gemini;

// "생각하는(thinking)" 모델이 실제 답변을 생성하기 전에 내부적으로 쓰는 사고 토큰의
// 예산을 조절한다. thinkingBudget을 0으로 두면 그 사고 단계를 건너뛰고 바로 답변을
// 생성하므로, 답변까지 걸리는 시간이 줄어든다 — 특히 "-lite" 계열처럼 속도가
// 우선인 모델에서 Google이 권장하는 설정이다.
public record GeminiThinkingConfig(Integer thinkingBudget) {
}
