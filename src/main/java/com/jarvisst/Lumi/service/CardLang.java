package com.jarvisst.Lumi.service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 결과 카드의 고정 문구를 사용자가 말한 언어(ko·en·ja·zh·es)로 만든다. 검색 제목·본문처럼 외부에서 온 글은 그대로 두고,
 * "검색 결과 3건 중 핵심만 정리했어요" 같은 우리 쪽 문구만 바꾼다. 모르는 키·언어는 한국어로 내려간다.
 */
public final class CardLang {

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]");
    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30FF]");
    private static final Pattern HAN = Pattern.compile("[\\u4E00-\\u9FFF]");
    private static final Pattern LATIN = Pattern.compile("\\p{IsLatin}");
    private static final Pattern SPANISH = Pattern.compile(
            "[¿¡ñáéíóú]|\\b(qué|cómo|cuál|dónde|hola|gracias|explica|dime|puedes|por favor|el tiempo|noticias|hoy|mañana)\\b",
            Pattern.CASE_INSENSITIVE);

    private CardLang() {
    }

    /** 사용자가 쓴 글에서 언어를 추정한다. 판단할 글자가 없으면 ko. */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return "ko";
        }
        if (HANGUL.matcher(text).find()) {
            return "ko";
        }
        if (KANA.matcher(text).find()) {
            return "ja";
        }
        if (HAN.matcher(text).find()) {
            return "zh";
        }
        if (LATIN.matcher(text).find()) {
            return SPANISH.matcher(text).find() ? "es" : "en";
        }
        return "ko";
    }

    private static final Map<String, String[]> T = Map.ofEntries(
            // 순서: ko, en, ja, zh, es
            Map.entry("day.today", new String[]{"오늘", "Today", "今日", "今天", "Hoy"}),
            Map.entry("day.tomorrow", new String[]{"내일", "Tomorrow", "明日", "明天", "Mañana"}),
            Map.entry("sch.title", new String[]{"%s 일정", "%s schedule", "%sの予定", "%s日程", "Agenda de %s"}),
            Map.entry("sch.title.conflict", new String[]{"%s 겹치는 일정", "%s conflicting events", "%sの重なる予定", "%s冲突的日程", "Eventos solapados de %s"}),
            Map.entry("sch.lead", new String[]{"%s 일정 %d건이에요.", "%s: %d event(s).", "%sの予定は%d件です。", "%s共有%d项日程。", "%s: %d evento(s)."}),
            Map.entry("sch.note.all", new String[]{"일정 %d건을 모두 보여주고 있어요.", "Showing all %d events.", "予定%d件をすべて表示しています。", "已显示全部%d项日程。", "Mostrando los %d eventos."}),
            Map.entry("sch.note.conflict", new String[]{"예정된 일정 %d건 중 겹치는 %d건만 보여주고 있어요.", "Showing only the %2$d overlapping events out of %1$d upcoming.", "予定%d件のうち重なる%d件のみ表示しています。", "在%d项日程中仅显示冲突的%d项。", "Mostrando solo los %2$d eventos solapados de %1$d."}),
            Map.entry("sch.note.near", new String[]{"예정된 일정 %d건 중 가까운 %d건만 보여주고 있어요.", "Showing the nearest %2$d events out of %1$d upcoming.", "予定%d件のうち直近の%d件のみ表示しています。", "在%d项日程中仅显示最近的%d项。", "Mostrando los %2$d eventos más cercanos de %1$d."}),
            Map.entry("sch.overlap", new String[]{"%s–%s, %s %d분 겹쳐요.", "%s–%s: %s overlap for %d min.", "%s–%s、%s%d分重なっています。", "%s–%s，%s重叠%d分钟。", "%s–%s: %s se solapan %d min."}),
            Map.entry("sch.who.two", new String[]{"두 일정이", "the two events", "2つの予定が", "两项日程", "los dos eventos"}),
            Map.entry("sch.who.n", new String[]{"%d개 일정이", "%d events", "%d件の予定が", "%d项日程", "%d eventos"}),
            Map.entry("sch.conflict.some", new String[]{"겹치는 일정이 있어요.", "Some events overlap.", "重なる予定があります。", "有日程冲突。", "Hay eventos solapados."}),
            Map.entry("rem.title", new String[]{"다가오는 리마인더", "Upcoming reminders", "これからのリマインダー", "即将到来的提醒", "Próximos recordatorios"}),
            Map.entry("rem.lead", new String[]{"대기 중인 리마인더가 %d건 있어요.", "You have %d pending reminder(s).", "待機中のリマインダーが%d件あります。", "有%d条待处理的提醒。", "Tienes %d recordatorio(s) pendiente(s)."}),
            Map.entry("rem.more", new String[]{"외 %d건이 더 있어요.", "%d more not shown.", "ほか%d件あります。", "另有%d条未显示。", "%d más sin mostrar."}),
            Map.entry("search.title", new String[]{"검색 결과", "Search results", "検索結果", "搜索结果", "Resultados de búsqueda"}),
            Map.entry("search.lead", new String[]{"검색 결과 %d건 중 핵심만 정리했어요.", "Key points from %d search results.", "検索結果%d件から要点をまとめました。", "从%d条搜索结果中整理了要点。", "Puntos clave de %d resultados."}),
            Map.entry("chart.note", new String[]{"검색으로 확인한 수치예요. 정확한 값은 출처에서 다시 확인하세요.", "Figures found via search. Verify exact values at the source.", "検索で確認した数値です。正確な値は出典でご確認ください。", "这些数值来自搜索，准确数据请以来源为准。", "Cifras halladas en la búsqueda. Verifica los valores exactos en la fuente."}),
            Map.entry("stock.note", new String[]{"검색 결과 기준 시세예요. 실제 매매 전에 증권사 앱에서 확인하세요.", "Prices based on search results. Check your broker before trading.", "検索結果に基づく相場です。取引前に証券会社のアプリで確認してください。", "行情来自搜索结果，交易前请在券商应用中确认。", "Precios según la búsqueda. Compruébalos con tu bróker antes de operar."}),
            Map.entry("explain.note", new String[]{"검색 없이 Lumi가 설명한 내용이에요. 최신·정확한 정보는 따로 확인하세요.", "Explained by Lumi without searching. Check other sources for the latest, exact info.", "検索せずLumiが説明した内容です。最新・正確な情報は別途ご確認ください。", "这是Lumi未经搜索给出的说明，最新准确的信息请另行确认。", "Explicado por Lumi sin buscar. Consulta otras fuentes para datos exactos y recientes."}),
            Map.entry("explain.wiki", new String[]{"위키백과", "Wikipedia", "ウィキペディア", "维基百科", "Wikipedia"}),
            Map.entry("explain.imgcredit", new String[]{"그림: 위키백과 (라이선스는 원문 페이지 참고)", "Image: Wikipedia (see the source page for its license)", "画像: ウィキペディア(ライセンスは元ページを参照)", "图片：维基百科（授权请见原页面）", "Imagen: Wikipedia (licencia en la página de origen)"})
    );

    private static int index(String lang) {
        if (lang == null) {
            return 0;
        }
        return switch (lang) {
            case "en" -> 1;
            case "ja" -> 2;
            case "zh" -> 3;
            case "es" -> 4;
            default -> 0;
        };
    }

    /** 키에 맞는 문구를 lang으로. 인자는 String.format 방식(%s, %d, %1$s). */
    public static String t(String key, String lang, Object... args) {
        String[] row = T.get(key);
        if (row == null) {
            return key;
        }
        String pattern = row[index(lang)];
        return args == null || args.length == 0 ? pattern : String.format(pattern, args);
    }
}
