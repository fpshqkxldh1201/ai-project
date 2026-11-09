package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.chat.ResultCard;
import com.jarvisst.Lumi.security.InputSanitizer;
import com.jarvisst.Lumi.security.SensitiveDataMasker;
import com.jarvisst.Lumi.service.CardLang;
import com.jarvisst.Lumi.service.ScreenAnalysisService;
import com.jarvisst.Lumi.service.SpeakerIntentService;
import com.jarvisst.Lumi.service.SpeakerIntentService.Item;
import com.jarvisst.Lumi.service.SpeakerIntentService.Outcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 상대방이 한 말의 의도 알려 주기. 로그인한 사용자만(SecurityConfig의 anyRequest().authenticated()).
 *  POST /api/intent/other        {text, relation, lang}  → {card} 또는 {error}   (글은 줄마다 한 문장, 최대 8줄)
 *  POST /api/intent/other/screen {image, relation, lang} → {card} 또는 {error}   (대화 캡처에서 상대 말을 읽어 분류)
 *  POST /api/intent/other/review {text, relation, lang}  → {card} 또는 {error}   (결과가 틀린 것 같을 때: 로컬 분류기 + 외부 AI를 비교)
 * relation: parent | partner | friend | boss | colleague | other
 *
 * 글은 로컬 분류기로만 보내고 저장·로그하지 않는다. 캡처는 글자 읽기용으로 Gemini를 한 번 쓰므로 한도를 더 엄격하게 둔다.
 */
@RestController
@RequestMapping("/api/intent/other")
public class SpeakerIntentController {

    private static final int MAX_TOTAL_CHARS = 2000;
    private static final long WINDOW_MS = 10 * 60_000L;
    private static final int TEXT_PER_WINDOW = 30;
    private static final int SCREEN_PER_WINDOW = 6;
    private static final int REVIEW_PER_WINDOW = 10;

    private final SpeakerIntentService service;
    private final InputSanitizer sanitizer;
    private final SensitiveDataMasker masker;
    private final Semaphore screenGate = new Semaphore(2);
    private final Map<String, Deque<Long>> textRecent = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> screenRecent = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> reviewRecent = new ConcurrentHashMap<>();
    private final Semaphore reviewGate = new Semaphore(3);

    public SpeakerIntentController(SpeakerIntentService service, InputSanitizer sanitizer, SensitiveDataMasker masker) {
        this.service = service;
        this.sanitizer = sanitizer;
        this.masker = masker;
    }

    public record TextRequest(String text, String relation, String lang) {
    }

    public record ScreenRequest(String image, String relation, String lang) {
    }

    @PostMapping
    public ResponseEntity<?> text(@RequestBody TextRequest req, Principal principal) {
        List<String> lines = new ArrayList<>();
        ResponseEntity<?> err = parseLines(req, lines);
        if (err != null) {
            return err;
        }
        if (!allow(textRecent, who(principal), TEXT_PER_WINDOW)) {
            return tooMany("분석을 너무 자주 했어요. 잠시 후 다시 시도해주세요.");
        }
        String rel = SpeakerIntentService.relationKey(req.relation());
        return respond(lines, rel, langOf(req.lang(), String.join(" ", lines)));
    }

    /** 검토: 사용자가 직접 눌렀을 때만 호출된다. 글이 외부 AI로 나가므로 한도를 둔다. */
    @PostMapping("/review")
    public ResponseEntity<?> review(@RequestBody TextRequest req, Principal principal) {
        List<String> lines = new ArrayList<>();
        ResponseEntity<?> err = parseLines(req, lines);
        if (err != null) {
            return err;
        }
        if (!allow(reviewRecent, who(principal), REVIEW_PER_WINDOW)) {
            return tooMany("검토를 너무 자주 했어요. 잠시 후 다시 시도해주세요.");
        }
        boolean got = false;
        try {
            got = reviewGate.tryAcquire(2, TimeUnit.SECONDS);
            if (!got) {
                return tooMany("지금은 다른 검토가 진행 중이에요.");
            }
            String rel = SpeakerIntentService.relationKey(req.relation());
            List<Item> items = new ArrayList<>();
            for (String s : lines) {
                items.add(new Item(s, rel));
            }
            Outcome out = service.classify(items);
            if (out.status() != SpeakerIntentService.Status.OK || out.predictions().size() != lines.size()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "의도 분류 서버에 연결할 수 없어요. 감정 서버(8002)가 켜져 있는지 확인해 주세요."));
            }
            ResultCard card = service.review(lines, rel, out.predictions(), langOf(req.lang(), String.join(" ", lines)));
            if (card == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "지금은 외부 AI의 검토를 받을 수 없어요. 잠시 후 다시 시도해주세요."));
            }
            return ResponseEntity.ok(Map.of("card", card));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "중단됐어요."));
        } finally {
            if (got) {
                reviewGate.release();
            }
        }
    }

    @PostMapping("/screen")
    public ResponseEntity<?> screen(@RequestBody ScreenRequest req, Principal principal) {
        if (req == null || req.image() == null || req.image().length() > ScreenAnalysisService.MAX_B64 + 100) {
            return bad("이미지가 올바르지 않아요.");
        }
        if (!allow(screenRecent, who(principal), SCREEN_PER_WINDOW)) {
            return tooMany("대화 캡처 분석은 잠시 후에 다시 할 수 있어요.");
        }
        boolean got = false;
        try {
            got = screenGate.tryAcquire(2, TimeUnit.SECONDS);
            if (!got) {
                return tooMany("지금은 다른 분석이 진행 중이에요.");
            }
            SpeakerIntentService.Extract ex = service.extractFromScreen(req.image());
            if (ex.error() != null) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", ex.error()));
            }
            String rel = SpeakerIntentService.relationKey(req.relation());
            return respond(ex.messages(), rel, langOf(req.lang(), String.join(" ", ex.messages())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "중단됐어요."));
        } finally {
            if (got) {
                screenGate.release();
            }
        }
    }

    /** 입력 글을 줄 단위로 정리·검증한다. 문제가 있으면 오류 응답을, 괜찮으면 null을 돌려주고 lines를 채운다. */
    private ResponseEntity<?> parseLines(TextRequest req, List<String> lines) {
        if (req == null || req.text() == null || req.text().length() > MAX_TOTAL_CHARS * 2) {
            return bad("글이 올바르지 않아요.");
        }
        String clean = sanitizer.sanitizeUserText(req.text());
        for (String line : clean.split("\\R")) {
            String s = masker.mask(line.strip().replaceAll("\\s{2,}", " ")).text();
            if (!s.isBlank()) {
                lines.add(s);
            }
        }
        if (lines.isEmpty()) {
            return bad("상대가 한 말을 입력해 주세요.");
        }
        if (lines.size() > SpeakerIntentService.MAX_MESSAGES) {
            return bad("한 번에 최대 " + SpeakerIntentService.MAX_MESSAGES + "줄까지 분석해요. 줄마다 상대의 말 한 문장씩 넣어 주세요.");
        }
        int total = 0;
        for (String s : lines) {
            if (s.length() > SpeakerIntentService.MAX_CHARS) {
                return bad("한 문장은 " + SpeakerIntentService.MAX_CHARS + "자 이하로 넣어 주세요.");
            }
            total += s.length();
        }
        if (total > MAX_TOTAL_CHARS) {
            return bad("글이 너무 길어요.");
        }
        return null;
    }

    private ResponseEntity<?> respond(List<String> lines, String rel, String lang) {
        List<Item> items = new ArrayList<>();
        for (String s : lines) {
            items.add(new Item(s, rel));
        }
        Outcome out = service.classify(items);
        if (out.status() == SpeakerIntentService.Status.NOT_READY) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "의도 분류 모델이 아직 없어요. 감정 서버 폴더에서 train_speaker_intent.py를 실행한 뒤 serve.py를 다시 켜 주세요."));
        }
        if (out.status() != SpeakerIntentService.Status.OK || out.predictions().size() != lines.size()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "의도 분류 서버에 연결할 수 없어요. 감정 서버(8002)가 켜져 있는지 확인해 주세요."));
        }
        ResultCard card = lines.size() == 1
                ? SpeakerIntentService.singleCard(lines.get(0), rel, out.predictions().get(0), lang)
                : SpeakerIntentService.multiCard(lines, rel, out.predictions(), lang);
        return ResponseEntity.ok(Map.of("card", card, "lines", lines));
    }

    private static String langOf(String requested, String text) {
        return "ko".equals(requested) || "en".equals(requested) ? requested : CardLang.detect(text);
    }

    private static String who(Principal p) {
        return p == null ? "anonymous" : p.getName();
    }

    private static ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static ResponseEntity<?> tooMany(String msg) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", msg));
    }

    /** 사용자별 10분 창 호출 제한. */
    private static boolean allow(Map<String, Deque<Long>> recent, String who, int perWindow) {
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= perWindow) {
                return false;
            }
            q.addLast(now);
        }
        if (recent.size() > 2000) {
            recent.clear();
        }
        return true;
    }
}
