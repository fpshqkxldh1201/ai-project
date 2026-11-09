package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.entity.ApiUsageDaily;
import com.jarvisst.Lumi.service.ApiUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final ApiUsageService apiUsageService;

    public UsageController(ApiUsageService apiUsageService) {
        this.apiUsageService = apiUsageService;
    }

    @GetMapping
    public Map<String, Object> getUsage() {
        List<ApiUsageDaily> last7 = apiUsageService.getLast7Days();
        List<Map<String, Object>> days = last7.stream()
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("date", u.getUsageDate().toString());
                    m.put("label", u.getUsageDate().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
                    m.put("count", u.getCount());
                    return m;
                })
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("today", apiUsageService.getTodayCount());
        result.put("dailyLimit", ApiUsageService.DAILY_LIMIT);
        result.put("last7Days", days);
        return result;
    }
}
