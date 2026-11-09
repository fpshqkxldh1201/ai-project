package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.security.SecurityEventResponse;
import com.jarvisst.Lumi.dto.security.SecurityStatusResponse;
import com.jarvisst.Lumi.service.SecurityStatusService;
import jakarta.servlet.http.HttpServletRequest;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.SecurityEventRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 본인에게 일어난 보안 이벤트(인젝션 탐지, 링크 차단, 속도 제한 등)를 최근 30건까지 조회한다. */
@RestController
@RequestMapping("/api/security")
public class SecurityEventController {

    private final SecurityEventRepository repository;
    private final CurrentUserProvider currentUserProvider;
    private final SecurityStatusService statusService;

    public SecurityEventController(SecurityEventRepository repository, CurrentUserProvider currentUserProvider,
                                   SecurityStatusService statusService) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
        this.statusService = statusService;
    }

    /** 화면 SECURITY STATUS 패널: 최근 24시간 요약 + 켜져 있는 방어 장치. */
    @GetMapping("/status")
    public SecurityStatusResponse status(HttpServletRequest request) {
        User user = currentUserProvider.requireCurrentUser();
        return statusService.statusFor(user.getId(), request.isSecure());
    }

    @GetMapping("/events")
    public List<SecurityEventResponse> myEvents() {
        User user = currentUserProvider.requireCurrentUser();
        return repository.findTop30ByUserIdOrderByOccurredAtDesc(user.getId()).stream()
                .map(SecurityEventResponse::from)
                .toList();
    }
}
