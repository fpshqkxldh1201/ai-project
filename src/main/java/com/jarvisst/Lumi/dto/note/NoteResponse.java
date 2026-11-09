package com.jarvisst.Lumi.dto.note;

import com.jarvisst.Lumi.entity.KnowledgeNote;

public record NoteResponse(Long id, String content, String createdAt, String source) {
    public static NoteResponse from(KnowledgeNote n) {
        return new NoteResponse(n.getId(), n.getContent(), n.getCreatedAt().toString(), n.getSource());
    }
}
