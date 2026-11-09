package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

    List<SecurityEvent> findTop30ByUserIdOrderByOccurredAtDesc(Long userId);

    List<SecurityEvent> findTop5ByUserIdOrderByOccurredAtDesc(Long userId);

    /** 종류별 발생 횟수(이 사용자, since 이후) — 보안 상태 패널의 24시간 요약용. */
    @Query("select e.eventType, count(e) from SecurityEvent e where e.userId = :userId and e.occurredAt >= :since group by e.eventType")
    List<Object[]> countByTypeSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
