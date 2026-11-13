package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.schedule.ScheduleResponse;
import com.jarvisst.Lumi.security.InputSanitizer;
import com.jarvisst.Lumi.service.ScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final InputSanitizer inputSanitizer;

    public ScheduleController(ScheduleService scheduleService, InputSanitizer inputSanitizer) {
        this.scheduleService = scheduleService;
        this.inputSanitizer = inputSanitizer;
    }

    /**
     * 지금 이후의 일정을 화면(SCHEDULE 패널)에 뿌리기 위한 조회용 엔드포인트.
     * Gemini function calling(get_schedules)과는 별개로, 프론트엔드가 페이지 로드 시
     * 직접 호출해서 실제 일정 데이터를 렌더링하는 데 사용한다.
     */
    @GetMapping
    public Map<String, List<ScheduleResponse>> getUpcomingSchedules() {
        List<ScheduleResponse> schedules = scheduleService.getUpcoming().stream()
                .map(ScheduleResponse::from)
                .toList();
        return Map.of("schedules", schedules);
    }

    /** 일정 수정 요청 본문. endTime·memo는 비워 두면 각각 시작+1시간·메모 없음. */
    public record UpdateRequest(String title, String startTime, String endTime, String memo) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody UpdateRequest req) {
        String title = inputSanitizer.cleanForStorage(req.title(), 100);
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "제목을 입력하세요."));
        }
        LocalDateTime start = parse(req.startTime());
        if (start == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "시작 시각 형식이 올바르지 않습니다."));
        }
        LocalDateTime end = (req.endTime() == null || req.endTime().isBlank()) ? null : parse(req.endTime());
        if (req.endTime() != null && !req.endTime().isBlank() && end == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "종료 시각 형식이 올바르지 않습니다."));
        }
        if (end != null && !end.isAfter(start)) {
            return ResponseEntity.badRequest().body(Map.of("error", "종료 시각은 시작 시각보다 뒤여야 합니다."));
        }
        String memo = inputSanitizer.cleanForStorage(req.memo(), 255);
        if (memo != null && memo.isBlank()) {
            memo = null;
        }
        return scheduleService.update(id, title, start, end, memo)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of(
                        "schedule", ScheduleResponse.from(r.saved()),
                        "hasConflict", !r.overlapping().isEmpty())))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "해당 일정을 찾지 못했습니다.")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        return scheduleService.delete(id)
                ? ResponseEntity.ok(Map.of("deleted", 1))
                : ResponseEntity.status(404).body(Map.of("error", "해당 일정을 찾지 못했습니다."));
    }

    /** 지금 이후의 같은 제목 일정을 모두 지운다(매주 반복 등록한 수업 등). */
    @DeleteMapping
    public ResponseEntity<?> deleteSeries(@RequestParam("title") String title) {
        String clean = inputSanitizer.cleanForStorage(title, 100);
        if (clean == null || clean.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "제목이 필요합니다."));
        }
        return ResponseEntity.ok(Map.of("deleted", scheduleService.deleteUpcomingByTitle(clean)));
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
