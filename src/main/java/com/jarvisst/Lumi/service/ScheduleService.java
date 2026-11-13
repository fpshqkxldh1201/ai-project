package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.Schedule;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.ScheduleRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

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

    private static final Pattern DELETE_INTENT = Pattern.compile("삭제|지워|지우|없애|취소|빼\\s*줘|빼줘|제거");
    private static final Pattern EDIT_INTENT = Pattern.compile("수정|변경|바꿔|바꾸|옮겨|옮기|미뤄|미루|당겨|앞당|늦춰|고쳐|조정");

    /** 사용자의 이번 발화에 일정을 지우려는 말이 있었는가 — 외부 글에 속은 모델이 마음대로 지우지 못하게 하는 확인용. */
    public static boolean hasDeleteIntent(String userMessage) {
        return userMessage != null && DELETE_INTENT.matcher(userMessage).find();
    }

    /** 사용자의 이번 발화에 일정을 고치려는 말이 있었는가. */
    public static boolean hasEditIntent(String userMessage) {
        return userMessage != null && (EDIT_INTENT.matcher(userMessage).find());
    }

    /** 본인 일정 하나를 찾는다(남의 것이면 empty). */
    public Optional<Schedule> find(long id) {
        return repository.findByIdAndOwner(id, currentUserProvider.requireCurrentUser());
    }

    /** 본인 일정 하나를 고친다. 없거나 남의 것이면 empty. endTime이 없으면 시작+1시간. 겹치는 다른 일정도 함께 돌려준다. */
    public Optional<ScheduleAddResult> update(long id, String title, LocalDateTime startTime, LocalDateTime endTime, String memo) {
        User owner = currentUserProvider.requireCurrentUser();
        Optional<Schedule> found = repository.findByIdAndOwner(id, owner);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Schedule s = found.get();
        LocalDateTime actualEnd = (endTime != null) ? endTime : startTime.plusHours(1);
        s.update(title, startTime, actualEnd, memo);
        Schedule saved = repository.save(s);
        List<Schedule> overlapping = repository.findOverlapping(owner, startTime, actualEnd).stream()
                .filter(o -> !o.getId().equals(saved.getId())).toList();
        return Optional.of(new ScheduleAddResult(saved, overlapping));
    }

    /** 본인 일정 하나를 지운다. 없거나 남의 것이면 false. */
    public boolean delete(long id) {
        User owner = currentUserProvider.requireCurrentUser();
        Optional<Schedule> found = repository.findByIdAndOwner(id, owner);
        if (found.isEmpty()) {
            return false;
        }
        repository.delete(found.get());
        return true;
    }

    /** 지금 이후의 본인 일정 중 제목이 같은 것을 모두 지운다(반복 일정 한 번에 지우기). 지운 개수를 돌려준다. */
    public int deleteUpcomingByTitle(String title) {
        User owner = currentUserProvider.requireCurrentUser();
        List<Schedule> targets = repository.findByOwnerAndTitleAndStartTimeAfter(owner, title, LocalDateTime.now().minusMinutes(1));
        repository.deleteAll(targets);
        return targets.size();
    }

    public record ScheduleAddResult(Schedule saved, List<Schedule> overlapping) {
    }
}
