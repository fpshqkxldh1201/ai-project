package com.jarvisst.Lumi.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자별 "마지막 질문/답변(+검색 근거)"을 서버 메모리에 잠깐 보관한다. 교차 검증이 브라우저가
 * 보낸 값이 아니라 서버가 실제로 Gemini에게 받은 답변을 검증하게 하기 위함이다(클라이언트가
 * 임의 텍스트를 검증 요청에 실어 보내 외부 API를 공짜 프록시로 쓰는 것도 막는다). 새로고침해도
 * 유지되고, 6시간이 지나면 사라진다. 서버를 재시작하면 비워진다(DB에 저장하지 않음).
 */
@Component
public class LastExchangeStore {

    public record Exchange(String question, String answer, String evidence, long savedAtMillis) {
    }

    private static final long TTL_MILLIS = 6L * 60 * 60 * 1000;
    private static final int MAX_USERS = 1000;

    private final Map<Long, Exchange> store = new ConcurrentHashMap<>();

    public void put(Long userId, String question, String answer, String evidence) {
        long now = System.currentTimeMillis();
        if (store.size() >= MAX_USERS && !store.containsKey(userId)) {
            store.values().removeIf(e -> now - e.savedAtMillis() > TTL_MILLIS);
            if (store.size() >= MAX_USERS) {
                return; // 가득 찼으면 새 사용자는 저장하지 않는다(교차 검증만 "직전 답변 없음"이 됨).
            }
        }
        store.put(userId, new Exchange(question, answer, evidence == null ? "" : evidence, now));
    }

    public Exchange get(Long userId) {
        Exchange e = store.get(userId);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.savedAtMillis() > TTL_MILLIS) {
            store.remove(userId);
            return null;
        }
        return e;
    }

    /** 로그아웃할 때 지운다 — 공용 PC·중고 PC·서버 메모리에 대화 흔적이 남지 않게(사회공학 장: 잔존 데이터). */
    public void clear(Long userId) {
        if (userId != null) {
            store.remove(userId);
        }
    }
}
