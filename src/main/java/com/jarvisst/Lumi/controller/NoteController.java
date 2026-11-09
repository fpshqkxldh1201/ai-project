package com.jarvisst.Lumi.controller;

import com.jarvisst.Lumi.dto.note.NoteRequest;
import com.jarvisst.Lumi.dto.note.NoteResponse;
import com.jarvisst.Lumi.service.KnowledgeNoteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 지식 노트 조회·추가·삭제(모두 로그인한 사용자 본인 몫만). */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final KnowledgeNoteService noteService;

    public NoteController(KnowledgeNoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping
    public Map<String, List<NoteResponse>> list() {
        return Map.of("notes", noteService.list().stream().map(NoteResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody NoteRequest request) {
        KnowledgeNoteService.AddResult result = noteService.add(request.content(), "ui");
        if (!result.ok()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.error()));
        }
        return ResponseEntity.ok(NoteResponse.from(result.note()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return noteService.delete(id)
                ? ResponseEntity.ok(Map.of("deleted", 1))
                : ResponseEntity.status(404).body(Map.of("error", "노트를 찾을 수 없습니다."));
    }

    @DeleteMapping
    public Map<String, Integer> deleteAll() {
        return Map.of("deleted", noteService.deleteAll());
    }
}
