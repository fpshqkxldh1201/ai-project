package com.jarvisst.Lumi.dto.schedule;

import com.jarvisst.Lumi.entity.Schedule;

public record ScheduleResponse(
        Long id,
        String title,
        String startTime,
        String endTime,
        String memo
) {
    public static ScheduleResponse from(Schedule schedule) {
        return new ScheduleResponse(
                schedule.getId(),
                schedule.getTitle(),
                schedule.getStartTime().toString(),
                schedule.getEndTime() != null ? schedule.getEndTime().toString() : null,
                schedule.getMemo()
        );
    }
}
