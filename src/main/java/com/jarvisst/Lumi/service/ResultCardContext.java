package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.chat.ResultCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 번의 /api/chat 요청 동안 함수 호출 결과로 만들어진 카드를 모아 두는 곳.
 * 요청을 처리하는 스레드 안에서만 쓰이므로 ThreadLocal이면 충분하다(ChatController가 시작·수거·정리한다).
 * lang: 이번 요청에서 사용자가 쓴 언어 — 카드 고정 문구(ResultCardBuilder)가 이 언어로 만들어진다.
 */
public final class ResultCardContext {

    private static final int MAX_CARDS = 5;
    private static final ThreadLocal<List<ResultCard>> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> LANG = new ThreadLocal<>();

    private ResultCardContext() {
    }

    public static void begin() {
        begin("ko");
    }

    public static void begin(String lang) {
        CURRENT.set(new ArrayList<>());
        LANG.set(lang == null || lang.isBlank() ? "ko" : lang);
    }

    /** begin() 전이거나 모르는 값이면 ko. */
    public static String lang() {
        String l = LANG.get();
        return l == null ? "ko" : l;
    }

    /** begin() 전이면(다른 경로에서 chat()이 불린 경우) 아무 일도 하지 않는다. */
    public static void addAll(List<ResultCard> cards) {
        List<ResultCard> list = CURRENT.get();
        if (list == null || cards == null) {
            return;
        }
        for (ResultCard card : cards) {
            if (card != null && list.size() < MAX_CARDS) {
                list.add(card);
            }
        }
    }

    public static List<ResultCard> drain() {
        List<ResultCard> list = CURRENT.get();
        return list == null ? List.of() : List.copyOf(list);
    }

    public static void clear() {
        CURRENT.remove();
        LANG.remove();
    }
}
