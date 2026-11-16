package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.Reminder;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.ReminderRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

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

    private static final Pattern COMPLETE_INTENT = Pattern.compile("완료|끝냈|끝났|다\\s*했|해냈|마쳤|처리했");

    /** 사용자의 이번 발화에 "다 했어/완료" 같은 말이 있었는가 — 외부 글에 속은 모델이 마음대로 완료 처리하지 못하게 하는 확인용. */
    public static boolean hasCompleteIntent(String userMessage) {
        return userMessage != null && COMPLETE_INTENT.matcher(userMessage).find();
    }

    /** 본인 리마인더 하나를 찾는다(남의 것이면 empty). */
    public Optional<Reminder> find(Long id) {
        return repository.findByIdAndOwner(id, currentUserProvider.requireCurrentUser());
    }

    /** 본인 리마인더의 내용·알림 시각을 고친다. 없거나 남의 것이면 empty. */
    public Optional<Reminder> update(Long id, String content, LocalDateTime remindAt) {
        return find(id).map(r -> {
            r.update(content, remindAt);
            return repository.save(r);
        });
    }

    /** 본인 리마인더 하나를 지운다. 없거나 남의 것이면 false. */
    public boolean delete(Long id) {
        return find(id).map(r -> {
            repository.delete(r);
            return true;
        }).orElse(false);
    }
}
