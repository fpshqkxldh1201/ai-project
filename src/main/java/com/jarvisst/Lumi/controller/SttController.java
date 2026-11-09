package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.service.SttService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

/** 서버 음성 인식(STT). 로그인한 사용자만 쓸 수 있다(앱 전체 보안 설정에 따름). */
@RestController
@RequestMapping("/api/stt")
public class SttController {

    private final SttService sttService;

    public SttController(SttService sttService) {
        this.sttService = sttService;
    }

    /** 프론트가 서버 인식 엔진을 고를 수 있는지 확인하는 용도. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("available", sttService.isAvailable());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(value = "lang", required = false) String lang,
                                                          Principal principal) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > SttService.MAX_AUDIO_BYTES) {
            return ResponseEntity.status(file != null && file.getSize() > SttService.MAX_AUDIO_BYTES ? 413 : 400)
                    .body(Map.of("error", "음성 파일이 올바르지 않습니다."));
        }
        String user = principal != null ? principal.getName() : "anonymous";
        SttService.Result result = sttService.transcribe(file.getBytes(), lang, user);
        if (!result.ok()) {
            return ResponseEntity.status(result.status()).body(Map.of("error", result.error()));
        }
        return ResponseEntity.ok(Map.of("text", result.text()));
    }
}
