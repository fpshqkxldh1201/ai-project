package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.Reminder;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.ReminderRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReminderService {

    private final ReminderRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public ReminderService(ReminderRepository repository, CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 아직 완료되지 않았고 지금 이후로 알림 시각이 남아있는, 로그인한 사용자 본인의
     * 리마인더만 시각순으로 반환한다(권한 관리).
     */
    public List<Reminder> getUpcoming() {
        User owner = currentUserProvider.requireCurrentUser();
        return repository.findByOwnerAndCompletedFalseAndRemindAtAfterOrderByRemindAtAsc(
                owner, LocalDateTime.now().minusMinutes(1));
    }

    public Reminder add(String content, LocalDateTime remindAt) {
        User owner = currentUserProvider.requireCurrentUser();
        return repository.save(new Reminder(owner, content, remindAt));
    }

    /**
     * 본인 소유의 리마인더일 때만 완료 처리한다 — 다른 사용자의 리마인더 id를 넘겨도
     * (추측·순차 대입 등) 처리되지 않는다(권한 관리: IDOR 방지).
     */
    public boolean complete(Long id) {
        User owner = currentUserProvider.requireCurrentUser();
        return repository.findByIdAndOwner(id, owner).map(r -> {
            r.markCompleted();
            repository.save(r);
            return true;
        }).orElse(false);
    }
}
