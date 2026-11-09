package com.jarvisst.Lumi.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 화면에 "결과 카드"로 띄울 조회·검색·통계·개념 설명 결과. 프론트(ResultStage)가 type에 맞게 그린다.
 *
 * type: schedule | reminder | search | stats(원형·막대·선) | stock(캔들) | table | explain(개념 설명)
 * lang: 카드의 고정 문구·라벨 언어(ko|en|ja|zh|es) — 사용자가 말한 언어를 따른다.
 * formulas: 개념 설명 카드의 수식(LaTeX). 프론트가 KaTeX로 그리고, 글자만 출력하므로 HTML로 해석되지 않는다.
 * image: 개념 설명 카드의 그림(위키백과). 주소는 우리 서버 프록시(/api/media/…)라서 외부 주소가 화면에 직접 나가지 않는다.
 * detail: 개념 설명 카드의 자세한 설명(위키백과 요약).
 * links: 카드 아래의 바로가기(지도·원문 등). 프론트가 https 주소와 허용된 도메인만 열어 준다.
 * 값이 없는 필드는 JSON에서 빠진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultCard(
        String id,
        String type,
        String title,
        String lead,
        List<String> points,
        List<String> sources,
        Timeline timeline,
        Chart chart,
        Table table,
        String note,
        String lang,
        List<Formula> formulas,
        Image image,
        String detail,
        List<Link> links
) {

    /** 예전 10개 값 생성자(기존 코드·시험이 그대로 동작하도록 남겨 둔다). */
    public ResultCard(String id, String type, String title, String lead, List<String> points, List<String> sources,
                      Timeline timeline, Chart chart, Table table, String note) {
        this(id, type, title, lead, points, sources, timeline, chart, table, note, null, null, null, null, null);
    }

    /** 14개 값 생성자(links 도입 전 코드가 그대로 동작하도록 남겨 둔다). */
    public ResultCard(String id, String type, String title, String lead, List<String> points, List<String> sources,
                      Timeline timeline, Chart chart, Table table, String note, String lang,
                      List<Formula> formulas, Image image, String detail) {
        this(id, type, title, lead, points, sources, timeline, chart, table, note, lang, formulas, image, detail, null);
    }

    public ResultCard withLang(String newLang) {
        return new ResultCard(id, type, title, lead, points, sources, timeline, chart, table, note,
                newLang, formulas, image, detail, links);
    }

    public ResultCard withLinks(List<Link> newLinks) {
        return new ResultCard(id, type, title, lead, points, sources, timeline, chart, table, note,
                lang, formulas, image, detail, newLinks == null || newLinks.isEmpty() ? null : newLinks);
    }

    public ResultCard withTexts(String newLead, List<String> newPoints) {
        return new ResultCard(id, type, title, newLead, newPoints, sources, timeline, chart, table, note,
                lang, formulas, image, detail, links);
    }

    public ResultCard withConcept(List<Formula> newFormulas, Image newImage, String newDetail) {
        return new ResultCard(id, type, title, lead, points, sources, timeline, chart, table, note, lang,
                newFormulas == null || newFormulas.isEmpty() ? formulas : newFormulas,
                newImage == null ? image : newImage,
                newDetail == null || newDetail.isBlank() ? detail : newDetail, links);
    }

    /** 하루 중 일부 구간(from~to 시)의 일정 막대. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Timeline(int from, int to, List<Item> items) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Item(String title, String start, String end, Boolean conflict) {
        }
    }

    /**
     * kind: pie | bar | line | candle | plot
     * plot의 data는 [{name, pts:[[x,y],...]}, ...] — 함수 그래프(여러 선, 서버가 식으로 계산한 점)
     * pie·bar·line의 data는 [[라벨, 값], ...], candle의 data는 [{o,h,l,c,v}, ...]
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Chart(String kind, String unit, List<Object> data) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Table(List<String> cols, List<List<String>> rows, Integer highlight) {
    }

    /** 수식 하나: tex(LaTeX)와 한 줄 설명(caption, 없을 수 있음). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Formula(String tex, String caption) {
    }

    /** 바로가기 하나: label은 화면에 보이는 글자, url은 https 주소. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Link(String label, String url) {
    }

    /** 카드에 붙는 그림: url은 /api/media/… 프록시 주소, page는 출처 문서 주소(https 위키백과). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Image(String url, String caption, String credit, String page) {
    }
}
