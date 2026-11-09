package com.jarvisst.Lumi.dto.naver;

import java.util.List;

public record NaverSearchResponse(List<Item> items) {
    /** pubDate는 뉴스 검색에만 있다(웹문서 검색에서는 null). */
    public record Item(String title, String link, String description, String pubDate) {
    }
}
