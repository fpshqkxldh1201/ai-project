package com.jarvisst.Lumi.dto.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;

// text / functionCall / functionResponse 중 하나만 채워서 보낸다 (null 필드는 직렬화에서 제외)
//
// thoughtSignature: "생각하는(thinking)" 모델이 functionCall 파트에 함께 내려주는 불투명한
// 서명 값이다. 모델이 호출한 함수를 실행한 뒤 그 모델 턴(parts)을 대화 기록에 그대로 다시
// 돌려줄 때, 이 값이 없으면 "Function call is missing a thought_signature" 400 에러가 난다.
// 그래서 이 필드를 추가해 역직렬화 때 받은 값을 그대로 보관했다가, 같은 parts를 그대로
// 재전송할 때 함께 실려 나가도록 한다 (https://ai.google.dev/gemini-api/docs/thought-signatures).
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiPart(
        String text,
        GeminiFunctionCall functionCall,
        GeminiFunctionResponse functionResponse,
        String thoughtSignature
) {
    public static GeminiPart ofText(String text) {
        return new GeminiPart(text, null, null, null);
    }

    public static GeminiPart ofFunctionCall(GeminiFunctionCall functionCall) {
        return new GeminiPart(null, functionCall, null, null);
    }

    public static GeminiPart ofFunctionResponse(GeminiFunctionResponse functionResponse) {
        return new GeminiPart(null, null, functionResponse, null);
    }
}
