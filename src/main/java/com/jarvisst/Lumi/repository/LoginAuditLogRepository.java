package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.LoginAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginAuditLogRepository extends JpaRepository<LoginAuditLog, Long> {

    List<LoginAuditLog> findByUsernameOrderByOccurredAtDesc(String username);
}
