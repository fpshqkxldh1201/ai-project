package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.reminder.ReminderResponse;
import com.jarvisst.Lumi.security.InputSanitizer;
import com.jarvisst.Lumi.service.ReminderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 화면(UPCOMING REMINDERS 패널·요약 카드)용 엔드포인트. 로그인한 본인의, 아직 완료되지 않았고
 * 알림 시각이 남은 리마인더만 시각순으로 돌려주며, 수정·삭제·완료도 본인 것만 가능하다(ReminderService가 소유자로 확인).
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    /** 화면에 한 번에 보낼 최대 개수(패널은 몇 개만 보여주고, 카드는 개수만 쓴다). */
    private static final int MAX_ITEMS = 20;

    private final ReminderService reminderService;
    private final InputSanitizer inputSanitizer;

    public ReminderController(ReminderService reminderService, InputSanitizer inputSanitizer) {
        this.reminderService = reminderService;
        this.inputSanitizer = inputSanitizer;
    }

    @GetMapping
    public Map<String, List<ReminderResponse>> getUpcomingReminders() {
        List<ReminderResponse> reminders = reminderService.getUpcoming().stream()
                .limit(MAX_ITEMS)
                .map(ReminderResponse::from)
                .toList();
        return Map.of("reminders", reminders);
    }

    public record UpdateRequest(String content, String remindAt) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        String content = inputSanitizer.cleanForStorage(req.content(), 200);
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "내용을 입력하세요."));
        }
        LocalDateTime at = parse(req.remindAt());
        if (at == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "알림 시각 형식이 올바르지 않습니다."));
        }
        return reminderService.update(id, content, at)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of("reminder", ReminderResponse.from(r))))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "해당 리마인더를 찾지 못했습니다.")));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable Long id) {
        return reminderService.complete(id)
                ? ResponseEntity.ok(Map.of("completed", 1))
                : ResponseEntity.status(404).body(Map.of("error", "해당 리마인더를 찾지 못했습니다."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return reminderService.delete(id)
                ? ResponseEntity.ok(Map.of("deleted", 1))
                : ResponseEntity.status(404).body(Map.of("error", "해당 리마인더를 찾지 못했습니다."));
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
