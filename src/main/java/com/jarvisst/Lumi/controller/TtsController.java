package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.tts.TtsRequest;
import com.jarvisst.Lumi.service.FishAudioTtsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final FishAudioTtsService fishAudioTtsService;

    public TtsController(FishAudioTtsService fishAudioTtsService) {
        this.fishAudioTtsService = fishAudioTtsService;
    }

    /**
     * Fish Audio로 음성을 합성해 MP3 바이너리로 반환한다. 실패 시(키 미설정, API 오류,
     * 크레딧 소진 등) 503을 반환하며, 프론트엔드는 이 경우 브라우저 내장 speechSynthesis로
     * 자동 대체한다 — 이 엔드포인트가 죽어 있어도 TTS 자체는 계속 동작한다.
     */
    @PostMapping
    public ResponseEntity<byte[]> synthesize(@Valid @RequestBody TtsRequest request) {
        byte[] audio = fishAudioTtsService.synthesize(request.text(), request.language(), request.gender());
        if (audio == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("audio/mpeg"))
                .body(audio);
    }
}
