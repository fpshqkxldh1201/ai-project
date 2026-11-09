package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.EmotionLog;
import com.jarvisst.Lumi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EmotionLogRepository extends JpaRepository<EmotionLog, Long> {

    List<EmotionLog> findByOwnerAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(User owner, LocalDateTime from);

    long countByOwner(User owner);

    @Modifying
    @Query("DELETE FROM EmotionLog e WHERE e.owner = :owner")
    int deleteAllByOwner(@Param("owner") User owner);

    @Modifying
    @Query("DELETE FROM EmotionLog e WHERE e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
