package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.schedule.ScheduleResponse;
import com.jarvisst.Lumi.service.ScheduleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * 지금 이후의 일정을 화면(TODAY'S SCHEDULE 패널)에 뿌리기 위한 조회용 엔드포인트.
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
}
