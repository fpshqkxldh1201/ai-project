package com.jarvisst.Lumi.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 꼬리 탐색을 할지, 한다면 어떤 종류로 몇 단계를 할지 정한다. 모델 호출 없이 규칙만 쓰므로 비용이 없다.
 *
 * 종류: concept(개념 학습) · weather(날씨) · commute(등교·출근 경로와 교통) · none
 * 일정·알림·메모처럼 개인 데이터를 건드리는 말, 인사·잡담, 너무 짧은 말은 탐색하지 않는다.
 */
public final class DeepDivePlanner {

    private DeepDivePlanner() {
    }

    public enum Kind { CONCEPT, WEATHER, COMMUTE, NONE }

    public record Step(String id, String label) {
    }

    public record Plan(Kind kind, List<Step> steps) {
        public String kindName() {
            return kind.name().toLowerCase();
        }
    }

    private static final Plan NONE = new Plan(Kind.NONE, List.of());

    /** 개인 데이터·기능 호출 말투 → 탐색하지 않는다(일정 추가 같은 요청에 날씨 카드가 붙으면 안 된다). */
    private static final Pattern PERSONAL = Pattern.compile(
            "일정|스케줄|캘린더|약속|알림|리마인|기억해|메모|노트|삭제|지워|추가해|등록|저장해|예약|취소|변경|수정|유튜브|youtube|"
                    + "schedule|calendar|remind|\\bnote\\b|delete|remove",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WEATHER = Pattern.compile(
            "날씨|기온|온도|몇\\s*도|비\\s*(와|올|오나|오늘)|비가|눈\\s*(와|올|오나)|눈이|우산|미세먼지|초미세|황사|체감|일기\\s*예보|기상|"
                    + "weather|forecast|temperature|will it rain|umbrella|air quality|"
                    + "天気|天气|气温|clima|tiempo",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMUTE = Pattern.compile(
            "교통|지하철|버스|도로|막히|막혀|정체|경로|길\\s*찾|출근|퇴근|등교|통학|가는\\s*길|가는데|운행|지연|파업|시위|집회|"
                    + "traffic|commute|route|directions|how to get to|subway|bus delay|"
                    + "交通|道路|渋滞|通勤|tráfico|ruta",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONCEPT = Pattern.compile(
            "뭐야|뭔가요|무엇|뭐예요|뭐에요|란\\s*\\?|이란|에\\s*대해|대해\\s*(알려|설명)|알려\\s*줘|알려줘|설명해|설명\\s*좀|개념|원리|정의|공식|"
                    + "어떻게\\s*(푸|풀|계산|작동)|왜\\s*그런|차이점|"
                    + "what is|what are|explain|how does|how do|define|definition|derive|"
                    + "とは|について|什么是|是什么|解释|qué es|explica|cómo funciona",
            Pattern.CASE_INSENSITIVE);

    /** 일상 대화·감정·기능 호출에 가까운 짧은 말은 개념 질문으로 보지 않는다. */
    private static final Pattern SMALLTALK = Pattern.compile(
            "^(안녕|하이|헬로|고마워|감사|ㅎㅎ|ㅋㅋ|잘\\s*자|hello|hi|hey|thanks|thank you)\\b",
            Pattern.CASE_INSENSITIVE);

    public static Plan plan(String message, String reply, List<String> cardTypes) {
        if (message == null) {
            return NONE;
        }
        String m = message.strip();
        if (m.length() < 4 || m.length() > 300 || m.equals("(대화 시작)") || SMALLTALK.matcher(m).find()
                || PERSONAL.matcher(m).find()) {
            return NONE;
        }
        // 교통은 날씨보다 먼저: "등교길 비 오는데 교통 어때"는 경로 탐색이 더 알맞다.
        if (COMMUTE.matcher(m).find()) {
            return new Plan(Kind.COMMUTE, List.of(
                    new Step("route", "경로 비교"),
                    new Step("traffic", "교통 변수")));
        }
        if (WEATHER.matcher(m).find()) {
            return new Plan(Kind.WEATHER, List.of(
                    new Step("forecast", "예보"),
                    new Step("stats", "통계 추이"),
                    new Step("air", "대기 상태"),
                    new Step("ai", "AI 해석")));
        }
        boolean explainCard = cardTypes != null && cardTypes.contains("explain");
        if (explainCard || CONCEPT.matcher(m).find()) {
            if (reply == null || reply.strip().length() < 40) {
                return NONE; // 답이 너무 짧으면(거절·되묻기) 깊이 팔 것이 없다
            }
            return new Plan(Kind.CONCEPT, List.of(
                    new Step("outline", "구성"),
                    new Step("solve", "풀이"),
                    new Step("why", "원리"),
                    new Step("ask", "더 알아보기")));
        }
        return NONE;
    }
}
