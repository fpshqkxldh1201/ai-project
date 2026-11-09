package com.jarvisst.Lumi.dto.gemini;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GeminiRequest(
        @JsonProperty("system_instruction") GeminiSystemInstruction systemInstruction,
        List<GeminiContent> contents,
        List<GeminiTool> tools
) {
}
