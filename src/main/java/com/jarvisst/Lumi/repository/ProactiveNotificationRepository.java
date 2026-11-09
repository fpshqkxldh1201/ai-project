package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.ProactiveNotification;
import com.jarvisst.Lumi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProactiveNotificationRepository extends JpaRepository<ProactiveNotification, Long> {

    @Query("SELECT n FROM ProactiveNotification n WHERE n.owner = :owner "
            + "AND (n.delivered IS NULL OR n.delivered = false) ORDER BY n.createdAt ASC")
    List<ProactiveNotification> findPendingByOwner(@Param("owner") User owner);
}
