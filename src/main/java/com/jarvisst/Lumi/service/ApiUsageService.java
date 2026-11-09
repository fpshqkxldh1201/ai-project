package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.ApiUsageDaily;
import com.jarvisst.Lumi.repository.ApiUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ApiUsageService {

    // gemini-3.5-flash-lite 무료 티어 기준 일일 한도(대시보드 표시용 참고값).
    // 실제 한도가 바뀌면 이 값만 수정하면 된다.
    public static final int DAILY_LIMIT = 500;

    private final ApiUsageRepository repository;

    public ApiUsageService(ApiUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * Gemini generateContent를 한 번 호출할 때마다 오늘 날짜 카운트를 1 증가시킨다.
     * 함수 호출 루프가 여러 턴 반복되면 턴마다 한 번씩 호출되어 그만큼 누적된다.
     */
    @Transactional
    public void incrementToday() {
        LocalDate today = LocalDate.now();
        ApiUsageDaily usage = repository.findById(today).orElse(new ApiUsageDaily(today, 0));
        usage.increment();
        repository.save(usage);
    }

    public int getTodayCount() {
        return repository.findById(LocalDate.now()).map(ApiUsageDaily::getCount).orElse(0);
    }

    /**
     * 오늘을 포함한 최근 7일치 사용량을 날짜순으로 반환한다(기록이 없는 날은 0으로 채운다).
     */
    public List<ApiUsageDaily> getLast7Days() {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(6);
        List<ApiUsageDaily> existing = repository.findByUsageDateBetweenOrderByUsageDateAsc(from, today);

        List<ApiUsageDaily> result = new ArrayList<>();
        int idx = 0;
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            if (idx < existing.size() && existing.get(idx).getUsageDate().equals(d)) {
                result.add(existing.get(idx));
                idx++;
            } else {
                result.add(new ApiUsageDaily(d, 0));
            }
        }
        return result;
    }
}
