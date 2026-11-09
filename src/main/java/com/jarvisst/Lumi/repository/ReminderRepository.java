package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.Reminder;
import com.jarvisst.Lumi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByOwnerAndCompletedFalseAndRemindAtAfterOrderByRemindAtAsc(User owner, LocalDateTime from);

    // 완료 처리 전에 "본인 소유"인지 확인하기 위한 조회 (권한 관리: 다른 사용자의 리마인더 id를
    // 추측해서 호출해도 완료 처리되지 않도록 막는다)
    Optional<Reminder> findByIdAndOwner(Long id, User owner);

    // "스스로 말 걸기"용: 알림 시각이 지났지만(remindAt <= now) 아직 완료되지 않았고 아직
    // 한 번도 알리지 않은 리마인더를 사용자를 가리지 않고 전부 찾는다(스케줄러가 모든 사용자를
    // 대상으로 돈다). from으로 너무 오래된(서버가 꺼져 있던 동안 쌓인) 것까지 한꺼번에
    // 몰아서 알리지 않도록 하한선을 둔다.
    @Query("SELECT r FROM Reminder r JOIN FETCH r.owner WHERE r.completed = false AND (r.notified IS NULL OR r.notified = false) "
            + "AND r.remindAt > :from AND r.remindAt <= :to")
    List<Reminder> findDueForProactiveNotification(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
