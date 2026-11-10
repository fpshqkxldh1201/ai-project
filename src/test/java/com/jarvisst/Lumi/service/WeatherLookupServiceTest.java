package com.jarvisst.Lumi.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherLookupServiceTest {

    @Test
    void 지역후보_군더더기_제거() {
        assertEquals(List.of("서울 구로구", "구로구", "서울"), WeatherLookupService.candidates("서울 구로구 날씨"));
        assertEquals(List.of("서울 구로구", "구로구", "서울"), WeatherLookupService.candidates("내일 대한민국 서울 구로구 날씨 알려줘"));
        assertEquals(List.of("부산"), WeatherLookupService.candidates("부산 날씨"));
    }

    @Test
    void 의정부_글자가_깎이지_않는다() {
        assertEquals(List.of("의정부"), WeatherLookupService.candidates("의정부 날씨"));
    }

    @Test
    void 지역후보_비었을때() {
        assertTrue(WeatherLookupService.candidates("오늘 날씨 알려줘").isEmpty());
        assertTrue(WeatherLookupService.candidates(null).isEmpty());
    }

    @Test
    void 내장표_도시와_구() {
        var p = KoreanPlaces.find(WeatherLookupService.tokens("서울 구로구 날씨")).orElseThrow();
        assertEquals("서울 구로구", p.name());
        assertEquals(37.4954, p.lat(), 0.001);
    }

    @Test
    void 내장표_구만_말해도_한곳뿐이면_찾는다() {
        assertEquals("서울 구로구", KoreanPlaces.find(WeatherLookupService.tokens("구로구 날씨")).orElseThrow().name());
    }

    @Test
    void 내장표_이름이_겹치는_구는_도시가_있어야_한다() {
        assertTrue(KoreanPlaces.find(WeatherLookupService.tokens("중구 날씨")).isEmpty());
        assertEquals("부산 중구", KoreanPlaces.find(WeatherLookupService.tokens("부산 중구 날씨")).orElseThrow().name());
    }

    @Test
    void 내장표_시_표기와_조사() {
        assertEquals("서울", KoreanPlaces.find(WeatherLookupService.tokens("서울의 날씨")).orElseThrow().name());
        assertEquals("서울", KoreanPlaces.find(WeatherLookupService.tokens("서울특별시 날씨")).orElseThrow().name());
        assertEquals("의정부", KoreanPlaces.find(WeatherLookupService.tokens("경기도 의정부시 날씨")).orElseThrow().name());
    }

    @Test
    void 거리계산() {
        var seoul = new DeepDiveSources.Place("서울", 37.5665, 126.9780);
        var busan = new DeepDiveSources.Place("부산", 35.1796, 129.0756);
        double d = WeatherLookupService.distanceKm(seoul, busan);
        assertTrue(d > 300 && d < 360, "서울-부산 약 325km: " + d);
    }
}
