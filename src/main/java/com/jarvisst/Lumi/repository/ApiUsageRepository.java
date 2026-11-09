package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.ApiUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ApiUsageRepository extends JpaRepository<ApiUsageDaily, LocalDate> {

    List<ApiUsageDaily> findByUsageDateBetweenOrderByUsageDateAsc(LocalDate from, LocalDate to);
}
