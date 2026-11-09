package com.jarvisst.Lumi.dto.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoteRequest(@NotBlank @Size(max = 400) String content) {
}
