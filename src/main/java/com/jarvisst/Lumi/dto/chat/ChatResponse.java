package com.jarvisst.Lumi.dto.chat;

import java.util.List;

/**
 * translatable: 외국어 답변이라 한국어 번역을 /api/chat/translate로 따로 받을 수 있으면 true(번역은 화면 표시용, 음성으로 읽지 않음).
 * cards: 조회·검색·통계 결과를 화면에 띄울 카드(없으면 빈 목록). 프론트가 코어 주변에 입체 카드로 그린다.
 */
public record ChatResponse(String reply, EmotionInfo emotion, List<ChatLink> links, boolean translatable,
                           List<ResultCard> cards) {

    /** 카드가 없는 일반 응답용. */
    public ChatResponse(String reply, EmotionInfo emotion, List<ChatLink> links, boolean translatable) {
        this(reply, emotion, links, translatable, List.of());
    }

    /**
     * valence/arousal: 기분의 방향(-1 부정 ~ +1 긍정)과 에너지(-1 낮음 ~ +1 높음), 화면의 배지 색에 쓴다.
     * toneLabel: 이번 답변에 실제로 반영한 톤 이름(반영하지 않았으면 null).
     * ambiguous: 글만으로는 감정을 읽기 어려워 사진을 청할 만하면 true.
     */
    public record EmotionInfo(String primaryEmotion, String secondaryEmotion, double confidence,
                              double valence, double arousal, String toneLabel, boolean ambiguous) {

        /** 예전 6개 값 생성자(기존 코드·시험이 그대로 동작하도록 남겨 둔다). */
        public EmotionInfo(String primaryEmotion, String secondaryEmotion, double confidence,
                           double valence, double arousal, String toneLabel) {
            this(primaryEmotion, secondaryEmotion, confidence, valence, arousal, toneLabel, false);
        }
    }
}
