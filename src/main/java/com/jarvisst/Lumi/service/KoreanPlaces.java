package com.jarvisst.Lumi.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 한국 지역 좌표 표(구청·시청 소재지 기준의 대략적인 중심). Open-Meteo 지오코딩이 "서울", "구로구" 같은
 * 한글 지명을 못 찾는 경우가 있어서 자주 묻는 지역은 이 표로 먼저 찾는다. 날씨 예보 격자(수 km)에는 충분한 정밀도다.
 * 같은 이름의 구가 여러 도시에 있으면("중구", "동구") 도시 이름이 함께 있을 때만 확정한다.
 */
final class KoreanPlaces {

    private record Entry(String city, String district, double lat, double lon) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    /** 도시(시·도 기준) → 중심 좌표 */
    private static final Map<String, double[]> CITIES = new HashMap<>();

    private static void city(String name, double lat, double lon) {
        CITIES.put(name, new double[]{lat, lon});
    }

    private static void gu(String city, String district, double lat, double lon) {
        ENTRIES.add(new Entry(city, district, lat, lon));
    }

    static {
        // 특별시·광역시·특별자치
        city("서울", 37.5665, 126.9780);
        city("부산", 35.1796, 129.0756);
        city("대구", 35.8714, 128.6014);
        city("인천", 37.4563, 126.7052);
        city("광주", 35.1595, 126.8526);
        city("대전", 36.3504, 127.3845);
        city("울산", 35.5384, 129.3114);
        city("세종", 36.4800, 127.2890);
        city("제주", 33.4996, 126.5312);
        // 경기
        city("수원", 37.2636, 127.0286);
        city("성남", 37.4200, 127.1265);
        city("의정부", 37.7381, 127.0337);
        city("안양", 37.3943, 126.9568);
        city("부천", 37.5034, 126.7660);
        city("광명", 37.4786, 126.8644);
        city("평택", 36.9921, 127.1129);
        city("안산", 37.3219, 126.8309);
        city("고양", 37.6584, 126.8320);
        city("과천", 37.4292, 126.9876);
        city("구리", 37.5943, 127.1296);
        city("남양주", 37.6360, 127.2165);
        city("오산", 37.1499, 127.0773);
        city("시흥", 37.3800, 126.8030);
        city("군포", 37.3617, 126.9352);
        city("의왕", 37.3449, 126.9683);
        city("하남", 37.5393, 127.2148);
        city("용인", 37.2411, 127.1776);
        city("파주", 37.7599, 126.7800);
        city("이천", 37.2720, 127.4350);
        city("김포", 37.6153, 126.7156);
        city("화성", 37.1995, 126.8312);
        city("양주", 37.7853, 127.0457);
        city("동두천", 37.9036, 127.0606);
        city("포천", 37.8949, 127.2003);
        city("여주", 37.2984, 127.6372);
        city("안성", 37.0080, 127.2797);
        city("가평", 37.8315, 127.5095);
        city("양평", 37.4917, 127.4875);
        // 그 밖의 주요 도시
        city("춘천", 37.8813, 127.7298);
        city("강릉", 37.7519, 128.8761);
        city("원주", 37.3422, 127.9202);
        city("속초", 38.2070, 128.5918);
        city("청주", 36.6424, 127.4890);
        city("천안", 36.8151, 127.1139);
        city("전주", 35.8242, 127.1480);
        city("군산", 35.9676, 126.7368);
        city("익산", 35.9483, 126.9577);
        city("목포", 34.8118, 126.3922);
        city("여수", 34.7604, 127.6622);
        city("순천", 34.9507, 127.4872);
        city("포항", 36.0190, 129.3435);
        city("경주", 35.8562, 129.2247);
        city("창원", 35.2280, 128.6811);
        city("진주", 35.1800, 128.1076);
        city("김해", 35.2285, 128.8894);
        city("구미", 36.1195, 128.3446);
        city("안동", 36.5684, 128.7294);

        // 서울 25개 구
        gu("서울", "강남구", 37.5172, 127.0473);
        gu("서울", "강동구", 37.5301, 127.1238);
        gu("서울", "강북구", 37.6396, 127.0257);
        gu("서울", "강서구", 37.5509, 126.8495);
        gu("서울", "관악구", 37.4784, 126.9516);
        gu("서울", "광진구", 37.5385, 127.0823);
        gu("서울", "구로구", 37.4954, 126.8874);
        gu("서울", "금천구", 37.4569, 126.8956);
        gu("서울", "노원구", 37.6542, 127.0568);
        gu("서울", "도봉구", 37.6688, 127.0471);
        gu("서울", "동대문구", 37.5744, 127.0396);
        gu("서울", "동작구", 37.5124, 126.9393);
        gu("서울", "마포구", 37.5663, 126.9019);
        gu("서울", "서대문구", 37.5791, 126.9368);
        gu("서울", "서초구", 37.4837, 127.0324);
        gu("서울", "성동구", 37.5634, 127.0369);
        gu("서울", "성북구", 37.5894, 127.0167);
        gu("서울", "송파구", 37.5145, 127.1059);
        gu("서울", "양천구", 37.5169, 126.8664);
        gu("서울", "영등포구", 37.5264, 126.8963);
        gu("서울", "용산구", 37.5324, 126.9906);
        gu("서울", "은평구", 37.6027, 126.9291);
        gu("서울", "종로구", 37.5735, 126.9790);
        gu("서울", "중구", 37.5638, 126.9976);
        gu("서울", "중랑구", 37.6066, 127.0926);
        // 광역시의 자주 묻는 구
        gu("부산", "중구", 35.1063, 129.0322);
        gu("부산", "해운대구", 35.1631, 129.1639);
        gu("대구", "수성구", 35.8581, 128.6306);
        gu("대구", "중구", 35.8693, 128.6063);
        gu("인천", "연수구", 37.4101, 126.6783);
        gu("인천", "부평구", 37.5070, 126.7219);
        gu("인천", "남동구", 37.4470, 126.7314);
        gu("대전", "유성구", 36.3622, 127.3563);
        gu("성남", "분당구", 37.3828, 127.1189);
    }

    private KoreanPlaces() {
    }

    /** "서울특별시"→"서울", "의정부시"→"의정부", "서울의"→"서울". */
    static String normalize(String token) {
        if (token == null) {
            return "";
        }
        String t = token.strip();
        t = t.replaceAll("(특별자치시|특별자치도|특별시|광역시)$", "");
        if (t.length() > 2 && t.endsWith("시")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.length() >= 3 && t.endsWith("의")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * 말에서 뽑은 낱말들로 지역을 찾는다. 도시+구가 모두 있으면 그 구, 구만 있고 그 이름이 한 곳뿐이면 그 구,
     * 도시만 있으면 도시 중심. 이름이 겹치는 구("중구")를 도시 없이 물으면 empty.
     */
    static Optional<DeepDiveSources.Place> find(List<String> tokens) {
        String city = null;
        String district = null;
        for (String raw : tokens) {
            String t = normalize(raw);
            if (city == null && CITIES.containsKey(t)) {
                city = t;
                continue;
            }
            if (district == null && isDistrictName(t)) {
                district = t;
            }
        }
        if (city != null && district != null) {
            for (Entry e : ENTRIES) {
                if (e.city().equals(city) && e.district().equals(district)) {
                    return Optional.of(new DeepDiveSources.Place(city + " " + district, e.lat(), e.lon()));
                }
            }
        }
        if (city == null && district != null) {
            Entry only = null;
            for (Entry e : ENTRIES) {
                if (e.district().equals(district)) {
                    if (only != null) {
                        return Optional.empty(); // 이름이 겹침
                    }
                    only = e;
                }
            }
            if (only != null) {
                return Optional.of(new DeepDiveSources.Place(only.city() + " " + only.district(), only.lat(), only.lon()));
            }
        }
        if (city != null) {
            double[] c = CITIES.get(city);
            return Optional.of(new DeepDiveSources.Place(city, c[0], c[1]));
        }
        return Optional.empty();
    }

    private static boolean isDistrictName(String t) {
        for (Entry e : ENTRIES) {
            if (e.district().equals(t)) {
                return true;
            }
        }
        return false;
    }
}
