package com.jarvisst.Lumi.dto.chat;

import com.jarvisst.Lumi.dto.emotion.EmotionResult;

import java.util.List;

/**
 * evidence: Lumi가 이번 답변을 만들며 검색(Google/Naver)에서 얻은 근거 텍스트. 화면에는 보내지
 * 않고, 사용자가 요청하는 교차 검증에서 "답변이 근거와 맞는지" 대조하는 데만 쓴다.
 * toneLabel: 이번 답변에 실제로 반영한 감정 톤의 화면 표시용 이름(반영하지 않았으면 null).
 */
public record ChatResult(String reply, EmotionResult emotion, List<ChatLink> links, String evidence,
                         String toneLabel) {

    /** 링크·근거가 없는 일반 응답용. */
    public ChatResult(String reply, EmotionResult emotion) {
        this(reply, emotion, List.of(), "", null);
    }

    /** 감정 톤 반영 정보가 없는 응답용. */
    public ChatResult(String reply, EmotionResult emotion, List<ChatLink> links, String evidence) {
        this(reply, emotion, links, evidence, null);
    }
}
