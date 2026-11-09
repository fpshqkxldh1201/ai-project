package com.jarvisst.Lumi.dto.chat;

/**
 * 답변 아래에 카드로 보여줄 링크(유튜브 영상, 구글 검색 출처).
 * kind: "youtube" | "web". url은 항상 https로 시작하는 값만 담는다(서버에서 검증).
 */
public record ChatLink(String kind, String title, String subtitle, String url) {
}
