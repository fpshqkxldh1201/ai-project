package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.Schedule;
import com.jarvisst.Lumi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findByOwnerAndStartTimeAfterOrderByStartTimeAsc(User owner, LocalDateTime from);

    // 수정·삭제는 항상 "소유자 본인 것"만 찾는다(남의 id를 넣어도 못 찾는다)
    java.util.Optional<Schedule> findByIdAndOwner(Long id, User owner);

    List<Schedule> findByOwnerAndTitleAndStartTimeAfter(User owner, String title, LocalDateTime from);

    // 새 일정(start~end)이 "같은 사용자"의 기존 일정과 시간이 겹치는지 확인 (권한 관리: 다른 사용자
    // 일정과는 애초에 비교하지 않는다)
    @Query("SELECT s FROM Schedule s WHERE s.owner = :owner AND s.endTime IS NOT NULL "
            + "AND s.startTime < :end AND s.endTime > :start")
    List<Schedule> findOverlapping(@Param("owner") User owner,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    // "스스로 말 걸기"용: 시작 시각이 임박했고(now ~ now+N분) 아직 한 번도 알리지 않은 일정을
    // 사용자를 가리지 않고 전부 찾는다(스케줄러가 모든 사용자를 대상으로 돈다).
    @Query("SELECT s FROM Schedule s JOIN FETCH s.owner WHERE (s.notified IS NULL OR s.notified = false) "
            + "AND s.startTime >= :from AND s.startTime <= :to")
    List<Schedule> findDueForProactiveNotification(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
