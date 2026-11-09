package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.settings.SettingsRequest;
import com.jarvisst.Lumi.dto.settings.SettingsResponse;
import com.jarvisst.Lumi.entity.User;
import com.jarvisst.Lumi.repository.UserRepository;
import com.jarvisst.Lumi.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LUMI CONSOLE 설정(입력/답변 언어, 음성 성별, TTS on/off)을 로그인한 계정에 저장·조회한다.
 * 브라우저 localStorage 대신 DB에 저장해서, 다른 기기·브라우저에서 로그인해도 그리고
 * 서버를 재시작해도 설정이 그대로 유지된다.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;

    public SettingsController(CurrentUserProvider currentUserProvider, UserRepository userRepository) {
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
    }

    @GetMapping
    public SettingsResponse getSettings() {
        return toResponse(currentUserProvider.requireCurrentUser());
    }

    @PostMapping
    public SettingsResponse updateSettings(@Valid @RequestBody SettingsRequest request) {
        User user = currentUserProvider.requireCurrentUser();
        user.updateSettings(request.inputLang(), request.responseLang(), request.voiceGender(), request.ttsEnabled(),
                request.alwaysListening());
        user.updatePrivacySettings(request.emotionLogEnabled(), request.proactiveCareEnabled());
        userRepository.save(user);
        return toResponse(user);
    }

    private SettingsResponse toResponse(User user) {
        return new SettingsResponse(user.getInputLang(), user.getResponseLang(), user.getVoiceGender(),
                user.isTtsEnabled(), user.isAlwaysListening(), user.isEmotionLogEnabled(),
                user.isProactiveCareEnabled());
    }
}
