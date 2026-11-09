package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.Schedule;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.ScheduleRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScheduleService {

    private final ScheduleRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public ScheduleService(ScheduleRepository repository, CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 지금 이후의 일정을, 로그인한 사용자 본인 것만 시작 시각 순으로 반환한다(권한 관리).
     */
    public List<Schedule> getUpcoming() {
        User owner = currentUserProvider.requireCurrentUser();
        return repository.findByOwnerAndStartTimeAfterOrderByStartTimeAsc(owner, LocalDateTime.now().minusMinutes(1));
    }

    /**
     * 로그인한 사용자 본인 이름으로 일정을 등록한다. endTime이 없으면 startTime + 1시간으로
     * 자동 설정하고, 등록 전에 "본인"의 기존 일정과 겹치는지 체크해서 함께 반환한다
     * (실제 등록은 겹쳐도 막지 않음 — 판단은 자비스st가 대화로 안내).
     */
    public ScheduleAddResult add(String title, LocalDateTime startTime, LocalDateTime endTime, String memo) {
        User owner = currentUserProvider.requireCurrentUser();
        LocalDateTime actualEnd = (endTime != null) ? endTime : startTime.plusHours(1);
        List<Schedule> overlapping = repository.findOverlapping(owner, startTime, actualEnd);
        Schedule saved = repository.save(new Schedule(owner, title, startTime, actualEnd, memo));
        return new ScheduleAddResult(saved, overlapping);
    }

    public record ScheduleAddResult(Schedule saved, List<Schedule> overlapping) {
    }
}
