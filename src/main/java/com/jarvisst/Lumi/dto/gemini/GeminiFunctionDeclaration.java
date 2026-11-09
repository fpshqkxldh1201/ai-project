package com.jarvisst.Lumi.dto.gemini;

import java.util.Map;

public record GeminiFunctionDeclaration(String name, String description, Map<String, Object> parameters) {
}
