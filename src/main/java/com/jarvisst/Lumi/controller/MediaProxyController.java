package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.service.ConceptMediaService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 개념 카드의 위키백과 그림을 우리 서버를 거쳐 내려 준다. 로그인한 사용자만 부를 수 있고(SecurityConfig의 anyRequest().authenticated()),
 * 서버가 방금 등록해 둔 id만 열린다 — 임의의 외부 주소를 대신 받아 주는 통로가 아니다.
 */
@RestController
@RequestMapping("/api/media")
public class MediaProxyController {

    private final ConceptMediaService media;

    public MediaProxyController(ConceptMediaService media) {
        this.media = media;
    }

    @GetMapping("/wiki/{id}")
    public ResponseEntity<byte[]> wiki(@PathVariable String id) {
        return media.fetch(id)
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(img.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .body(img.bytes()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
