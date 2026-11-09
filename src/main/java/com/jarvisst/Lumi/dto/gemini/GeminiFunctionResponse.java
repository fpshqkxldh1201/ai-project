package com.jarvisst.Lumi.dto.gemini;

import java.util.Map;

public record GeminiFunctionResponse(String name, Map<String, Object> response) {
}
