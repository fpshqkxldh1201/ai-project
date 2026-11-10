package com.jarvisst.Lumi.dto.gemini;

// finishReason: STOP(정상) / MAX_TOKENS(출력 한도로 끊김) / SAFETY 등. 답변이 중간에 끊겼는지 알아내는 데 쓴다.
public record GeminiCandidate(GeminiContent content, String finishReason) {
}
