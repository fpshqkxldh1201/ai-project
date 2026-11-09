package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.service.EmotionLogService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 로그인한 사용자 본인의 감정 시계열(날짜별 요약)을 조회하거나 전부 삭제한다. */
@RestController
@RequestMapping("/api/emotions")
public class EmotionLogController {

    private final EmotionLogService emotionLogService;

    public EmotionLogController(EmotionLogService emotionLogService) {
        this.emotionLogService = emotionLogService;
    }

    @GetMapping("/daily")
    public Map<String, List<EmotionLogService.DayStat>> daily(@RequestParam(defaultValue = "7") int days) {
        return Map.of("days", emotionLogService.dailySummary(days));
    }

    @DeleteMapping
    public Map<String, Integer> deleteAll() {
        return Map.of("deleted", emotionLogService.deleteAllMine());
    }
}
