package com.jarvisst.Lumi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "api_usage_daily")
public class ApiUsageDaily {

    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(nullable = false)
    private int count;

    protected ApiUsageDaily() {
        // JPA
    }

    public ApiUsageDaily(LocalDate usageDate, int count) {
        this.usageDate = usageDate;
        this.count = count;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public int getCount() {
        return count;
    }

    public void increment() {
        this.count++;
    }
}
