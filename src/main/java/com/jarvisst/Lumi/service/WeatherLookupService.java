package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.chat.ResultCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 날씨 질문을 웹 검색 대신 예보 수치(Open-Meteo, API 키 없음)로 답하기 위한 조회 서비스.
 * 웹 검색은 "날씨 사이트 소개 문장"만 돌려줘서 기온·강수확률 같은 숫자를 못 얻는다.
 * 지역명 → 좌표 → 예보·대기질을 가져와 모델에게 줄 숫자 요약과 카드(기온 추이 차트)를 만든다.
 * 지역을 못 찾거나 예보가 비면 empty — 호출한 쪽이 기존 NAVER 검색 흐름으로 넘어간다.
 */
@Service
public class WeatherLookupService {

    private static final Logger log = LoggerFactory.getLogger(WeatherLookupService.class);

    /** 지역명이 아닌 말(때·질문 표현)을 걷어 낸다. */
    private static final Pattern NOISE = Pattern.compile(
            "(대한민국|우리나라|한국|오늘|내일|모레|글피|이번\\s*주|다음\\s*주|주말|현재|지금|요즘|"
                    + "날씨|기온|온도|몇\\s*도|예보|일기|기상|미세먼지|초미세먼지|대기질|"
                    + "알려\\s*줘|알려줘|알려|어때|어떄|어떻게|어떨까|\\?|!|,|\\.)");

    /** 같은 이름의 구·군이 여러 도시에 있어("중구") 엉뚱한 곳이 잡히는 것을 막는 거리 기준(km). */
    private static final double MAX_DISTANCE_KM = 80.0;

    /** 기준점으로 쓸 수 있는 큰 도시 이름(도·국가 단위는 좌표가 중심점이라 기준으로 쓰지 않는다). */
    private static final Pattern BIG_CITY = Pattern.compile("^(서울|부산|대구|인천|광주|대전|울산|세종|제주).*$");

    private final DeepDiveSources sources;

    public WeatherLookupService(DeepDiveSources sources) {
        this.sources = sources;
    }

    /** 조회 결과: 지역 이름, 모델에게 줄 숫자 요약, 화면에 붙일 카드(없을 수 있다). */
    public record Result(String place, String digest, ResultCard card) {
    }

    public Optional<Result> lookup(String query) {
        try {
            List<String> candidates = candidates(query);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            // 한글 지오코딩이 "서울"·"구로구"를 못 찾는 경우가 있어 내장 좌표 표를 먼저 쓴다.
            Optional<DeepDiveSources.Place> place = KoreanPlaces.find(tokens(query));
            if (place.isEmpty()) {
                place = resolvePlace(candidates);
            }
            if (place.isEmpty()) {
                log.info("[날씨] 지역을 찾지 못함 — NAVER 검색으로 넘김 (query={})", query);
                return Optional.empty();
            }
            DeepDiveSources.Place p = place.get();
            Map<String, Object> forecast = sources.forecast(p.lat(), p.lon());
            String digest = DeepDiveCards.weatherDigest(forecast, Map.of(), sources.airQuality(p.lat(), p.lon()));
            if (digest.isBlank()) {
                log.info("[날씨] 예보 수치가 비어 있음 — NAVER 검색으로 넘김 (place={})", p.name());
                return Optional.empty();
            }
            ResultCard card = DeepDiveCards.forecast(forecast, p.name(), "ko", p.lat(), p.lon());
            log.info("[날씨] Open-Meteo 예보 사용 place={} (카드 {})", p.name(), card == null ? "없음" : "있음");
            return Optional.of(new Result(p.name(), digest, card));
        } catch (Exception e) {
            log.warn("[날씨] 조회 중 오류 — NAVER 검색으로 넘김: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 검색어("서울 구로구 날씨")에서 지역 후보를 뽑는다. 군더더기를 걷어 낸 전체 문구, 그다음 뒤쪽 낱말(더 구체적인 지명)부터 앞쪽 순서.
     */
    /** 군더더기를 걷어 낸 낱말들. */
    static List<String> tokens(String query) {
        if (query == null) {
            return List.of();
        }
        String cleaned = NOISE.matcher(query).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return cleaned.isEmpty() ? List.of() : List.of(cleaned.split(" "));
    }

    static List<String> candidates(String query) {
        List<String> toks = tokens(query);
        if (toks.isEmpty()) {
            return List.of();
        }
        String cleaned = String.join(" ", toks);
        String[] tokens = toks.toArray(new String[0]);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (tokens.length > 1) {
            out.add(cleaned);
        }
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (tokens[i].length() >= 2) {
                out.add(tokens[i]);
            }
        }
        return new ArrayList<>(out);
    }

    private Optional<DeepDiveSources.Place> resolvePlace(List<String> candidates) {
        // 가장 구체적인 후보부터 좌표를 찾는다(최대 3번).
        DeepDiveSources.Place specific = null;
        String used = null;
        int tries = 0;
        for (String c : candidates) {
            if (tries++ >= 3) {
                break;
            }
            Optional<DeepDiveSources.Place> p = sources.geocodeCity(c, "ko");
            if (p.isPresent()) {
                specific = p.get();
                used = c;
                break;
            }
        }
        if (specific == null) {
            return Optional.empty();
        }
        // 같은 이름의 구·군("중구")이 여러 도시에 있어 엉뚱한 곳이 잡힐 수 있다. 말 앞쪽의 시·도(기준점)와 너무 멀면 기준점을 쓴다.
        String head = candidates.get(candidates.size() - 1);
        if (candidates.size() > 1 && !head.equals(used) && BIG_CITY.matcher(head).matches()) {
            Optional<DeepDiveSources.Place> anchor = sources.geocodeCity(head, "ko");
            if (anchor.isPresent() && distanceKm(anchor.get(), specific) > MAX_DISTANCE_KM) {
                log.info("[날씨] '{}'이(가) '{}'에서 너무 멀어 시·도 기준으로 조회", used, head);
                return anchor;
            }
        }
        return Optional.of(specific);
    }

    static double distanceKm(DeepDiveSources.Place a, DeepDiveSources.Place b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
