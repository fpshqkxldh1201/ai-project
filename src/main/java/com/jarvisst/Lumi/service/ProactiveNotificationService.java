package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.ProactiveNotification;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.ProactiveNotificationRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Lumi가 사용자 입력 없이 먼저 말을 거는 한 마디를 쌓아두고(queue) 내려주는(fetchPending) 창구.
 * 지금은 ProactiveNotificationScheduler(리마인더/일정 임박)만 queue를 호출하지만, 앞으로
 * 대화 중 요청받은 백그라운드 작업(검색, 긴 조회 등)이 끝났을 때 결과를 알려주는 기능도
 * 이 서비스 하나만 재사용하면 된다 — 사용자 소유를 가리지 않고 바로 꽂을 수 있는 공용 통로다.
 */
@Service
public class ProactiveNotificationService {

    private final ProactiveNotificationRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public ProactiveNotificationService(ProactiveNotificationRepository repository,
                                         CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    /** 스케줄러 등 HTTP 요청 스레드 밖에서도 호출되므로, 대상 사용자를 명시적으로 받는다. */
    public void queue(User owner, String message) {
        repository.save(new ProactiveNotification(owner, message));
    }

    /** 외국어 문구에 한국어 번역을 함께 쌓는다(번역은 화면에만 표시되고 음성으로는 읽지 않는다). */
    public void queue(User owner, String message, String translation) {
        repository.save(new ProactiveNotification(owner, message, translation));
    }

    /**
     * 로그인한 사용자 몫의 아직 못 가져간 알림을 전부 반환하면서, 즉시 "가져감" 처리한다
     * (재접속/네트워크 재시도 시 같은 알림이 중복으로 두 번 말해지지 않도록 — 한 번 못 받으면
     * 그 알림은 유실될 수 있다는 트레이드오프를 감수한 단순한 방식).
     */
    @Transactional
    public List<ProactiveNotification> fetchPendingAndMarkDelivered() {
        User owner = currentUserProvider.requireCurrentUser();
        List<ProactiveNotification> pending = repository.findPendingByOwner(owner);
        pending.forEach(ProactiveNotification::markDelivered);
        repository.saveAll(pending);
        return pending;
    }
}
