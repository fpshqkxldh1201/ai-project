package com.jarvisst.Lumi.security;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 악성 코드 배포 경로 차단 — 정보보안개론 6장(악성 코드)의 트로이 목마·PUP·매크로 바이러스 내용을 Lumi에 적용.
 *
 * Lumi는 검색 결과 링크 카드를 보여주고 답변 본문에 URL이 섞일 수도 있다. 검색 결과는 제3자가 만든
 * 데이터라서, 정상 문서처럼 보이는 링크가 실제로는 실행 파일 다운로드(트로이 목마·PUP)이거나
 * 매크로가 든 오피스 문서(매크로 바이러스), 또는 사용자를 속이는 주소(사회공학)일 수 있다.
 * 그래서 링크를 화면에 내보내기 전에 아래 조건 중 하나라도 걸리면 차단한다.
 *
 *  1) https가 아닌 주소(javascript:, data:, http: 등)
 *  2) 주소에 아이디@가 섞인 위장 URL (https://google.com@evil.com/ 같은 사회공학 기법)
 *  3) IP 주소 직접 접속, localhost·사설망 이름 (정상 서비스는 도메인을 쓰고, 내부 서버 접근 시도(SSRF 성격)도 막음)
 *  4) 퓨니코드(xn--) 도메인 (유사 문자로 정상 사이트를 흉내 내는 호모그래프 공격)
 *  5) 실행 파일·스크립트·매크로 문서 확장자로 끝나는 주소 (.exe, .msi, .scr, .docm 등)
 *
 * 사회공학 장(피싱·스미싱·파밍)에서 추가한 조건:
 *  6) 단축 URL (최종 주소를 숨겨 스미싱에 자주 쓰임)
 *  7) 주소(호스트) 부분을 %인코딩으로 감춘 주소
 *  8) 유명 서비스를 흉내 낸 주소 — 철자만 바꾼 도메인(gooogle.com, paypa1.com), 정상 서비스 이름을 끼워 넣은
 *     주소(naver.com.evil.xyz, login-google.evil.com)
 *  9) 답변 속 [표시 주소](실제 주소) 링크에서 표시된 주소와 실제로 가는 주소가 다른 경우
 */
@Component
public class LinkSafetyChecker {

    /** 실행/설치/스크립트/매크로 문서 — 내려받아 열면 바로 위험해지는 확장자. */
    private static final List<String> DANGEROUS_EXTENSIONS = List.of(
            "exe", "msi", "scr", "com", "pif", "bat", "cmd", "lnk", "dll", "sys", "cpl", "reg", "hta",
            "vbs", "vbe", "wsf", "ps1", "jar", "apk", "dmg", "pkg", "iso", "js", "jse",
            "docm", "dotm", "xlsm", "xlam", "pptm", "ppam", "sldm");

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s)\\]>\"'<]+", Pattern.CASE_INSENSITIVE);

    private static final Set<String> SHORTENERS = Set.of(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "buff.ly", "rebrand.ly", "cutt.ly",
            "shorturl.at", "tiny.cc", "rb.gy", "han.gl", "me2.do", "url.kr", "vo.la", "zrr.kr", "bitly.com", "lnkd.in");

    /** 흉내 내기 대상 서비스 이름. 값은 그 서비스가 실제로 쓰는 도메인 목록. */
    private static final Map<String, Set<String>> BRANDS = Map.ofEntries(
            Map.entry("google", Set.of("google.com", "google.co.kr", "googleapis.com", "gstatic.com", "googleusercontent.com",
                    "google-analytics.com", "googlesyndication.com", "googleadservices.com", "googlevideo.com", "goo.gle")),
            Map.entry("youtube", Set.of("youtube.com", "youtu.be", "ytimg.com", "youtube-nocookie.com")),
            Map.entry("naver", Set.of("naver.com", "naver.net", "naver.me", "pstatic.net", "naver.co.kr")),
            Map.entry("kakao", Set.of("kakao.com", "kakao.co.kr", "kakaocdn.net", "kakaocorp.com")),
            Map.entry("daum", Set.of("daum.net", "daumcdn.net")),
            Map.entry("github", Set.of("github.com", "github.io", "githubusercontent.com", "githubassets.com")),
            Map.entry("apple", Set.of("apple.com", "icloud.com", "apple.news")),
            Map.entry("microsoft", Set.of("microsoft.com", "microsoftonline.com", "live.com", "office.com")),
            Map.entry("amazon", Set.of("amazon.com", "amazon.co.kr", "amazon.co.jp", "amazon.co.uk", "amazon.de")),
            Map.entry("instagram", Set.of("instagram.com", "cdninstagram.com")),
            Map.entry("facebook", Set.of("facebook.com", "fb.com", "fbcdn.net")),
            Map.entry("paypal", Set.of("paypal.com", "paypal.me")),
            Map.entry("coupang", Set.of("coupang.com", "coupangcdn.com")),
            Map.entry("netflix", Set.of("netflix.com", "nflxso.net")));

    /** 두 단계 공개 접미사(co.kr 등) — 등록 가능한 도메인을 자를 때 쓴다. */
    private static final Set<String> TWO_LEVEL_SUFFIX = Set.of(
            "co.kr", "or.kr", "go.kr", "ac.kr", "ne.kr", "re.kr", "pe.kr", "co.jp", "co.uk", "com.au", "com.cn", "com.br");

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]{1,300})\\]\\((https?://[^)\\s]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN_IN_LABEL = Pattern.compile(
            "(?:https?://)?((?:[a-z0-9-]+\\.)+[a-z]{2,})(?::\\d+)?(?:/\\S*)?", Pattern.CASE_INSENSITIVE);

    public static final String BLOCKED_PLACEHOLDER = "[차단된 링크]";

    public record TextScan(String text, int blocked) {
    }

    /** 차단해야 하면 사유(한국어 분류명)를, 안전하면 null을 돌려준다. */
    public String blockReason(String url) {
        if (url == null || url.isBlank() || url.length() > 500) {
            return "비정상 길이";
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return "해석할 수 없는 주소";
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return "https가 아닌 주소";
        }
        if (uri.getRawUserInfo() != null) {
            return "아이디@ 위장 주소";
        }
        String host = uri.getHost();
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority != null && rawAuthority.contains("%")) {
            return "주소 인코딩 위장";
        }
        if (host == null || host.isBlank()) {
            return "호스트 없음";
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") || host.contains(":") || IPV4.matcher(host).matches()) {
            return "IP 주소 직접 접속";
        }
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".localhost") || !host.contains(".")) {
            return "내부 주소";
        }
        for (String label : host.split("\\.")) {
            if (label.startsWith("xn--")) {
                return "퓨니코드 도메인";
            }
        }
        if (SHORTENERS.contains(host)) {
            return "단축 URL";
        }
        String lookalike = lookalikeReason(host);
        if (lookalike != null) {
            return lookalike;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot >= 0) {
            String ext = path.substring(dot + 1);
            if (DANGEROUS_EXTENSIONS.contains(ext)) {
                return "위험 확장자(." + ext + ")";
            }
        }
        return null;
    }

    public boolean isSafe(String url) {
        return blockReason(url) == null;
    }

    /** 문장 속 URL 중 위험한 것을 [차단된 링크]로 바꾼다. 안전한 URL은 그대로 둔다. */
    public TextScan maskUnsafeUrls(String text) {
        if (text == null || text.isEmpty()) {
            return new TextScan(text, 0);
        }
        int blocked = 0;
        // 마크다운 링크 [표시](실제)는 먼저 통째로 검사한다 — 눈에 보이는 글자와 실제 목적지가 다르면 피싱 수법이다.
        Matcher md = MD_LINK.matcher(text);
        StringBuilder mdOut = new StringBuilder();
        while (md.find()) {
            String reason = markdownMismatch(md.group(1), md.group(2));
            if (reason != null || blockReason(md.group(2).replaceAll("[.,;:!?]+$", "")) != null) {
                blocked++;
                md.appendReplacement(mdOut, Matcher.quoteReplacement(BLOCKED_PLACEHOLDER));
            } else {
                md.appendReplacement(mdOut, Matcher.quoteReplacement(md.group()));
            }
        }
        md.appendTail(mdOut);
        text = mdOut.toString();

        Matcher m = URL_IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String url = m.group();
            // 문장 끝 구두점이 URL에 딸려온 경우는 떼고 검사한다.
            String trimmed = url.replaceAll("[.,;:!?]+$", "");
            String tail = url.substring(trimmed.length());
            if (blockReason(trimmed) != null) {
                blocked++;
                m.appendReplacement(out, Matcher.quoteReplacement(BLOCKED_PLACEHOLDER + tail));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(url));
            }
        }
        m.appendTail(out);
        return new TextScan(out.toString(), blocked);
    }

    /** 등록 가능한 도메인(example.co.kr, naver.com)만 잘라낸다. */
    static String registrable(String host) {
        String[] labels = host.split("\\.");
        if (labels.length <= 2) {
            return host;
        }
        String lastTwo = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (TWO_LEVEL_SUFFIX.contains(lastTwo) && labels.length >= 3) {
            return labels[labels.length - 3] + "." + lastTwo;
        }
        return lastTwo;
    }

    /** 유명 서비스를 흉내 낸 주소면 사유를, 아니면 null. */
    private static String lookalikeReason(String host) {
        String reg = registrable(host);
        // 정상 도메인이면 통과(하위 도메인 포함: blog.naver.com, docs.github.com)
        for (Set<String> legit : BRANDS.values()) {
            if (legit.contains(reg)) {
                return null;
            }
        }
        String core = reg.substring(0, reg.indexOf('.'));
        String normalized = core.replace("0", "o").replace("1", "l").replace("rn", "m").replace("vv", "w");
        for (Map.Entry<String, Set<String>> e : BRANDS.entrySet()) {
            String brand = e.getKey();
            if (normalized.equals(brand) || core.equals(brand)) {
                return "유명 서비스(" + brand + ") 흉내 도메인";
            }
            if (brand.length() >= 6 && !core.equals(brand) && editDistance(core, brand) == 1) {
                return "유명 서비스(" + brand + ") 철자 위장 도메인";
            }
        }
        // 정상 서비스 이름을 앞쪽 주소나 이름 조각에 끼워 넣은 주소 (naver.com.evil.xyz, login-google.evil.com)
        for (String label : host.split("[.\\-]")) {
            if (BRANDS.containsKey(label)) {
                return "정상 서비스 이름(" + label + ")을 끼워 넣은 주소";
            }
        }
        return null;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    /** [표시 주소](실제 주소) 형태에서 두 주소의 도메인이 다르면 사유를, 아니면 null. */
    private static String markdownMismatch(String label, String target) {
        Matcher lm = DOMAIN_IN_LABEL.matcher(label.trim());
        if (!lm.matches()) {
            return null;
        }
        try {
            String shown = lm.group(1).toLowerCase(Locale.ROOT);
            String real = new URI(target).getHost();
            if (real == null) {
                return "표시 주소와 실제 주소 불일치";
            }
            real = real.toLowerCase(Locale.ROOT);
            if (!registrable(shown).equals(registrable(real))) {
                return "표시 주소와 실제 주소 불일치";
            }
        } catch (Exception e) {
            return "표시 주소와 실제 주소 불일치";
        }
        return null;
    }
}
