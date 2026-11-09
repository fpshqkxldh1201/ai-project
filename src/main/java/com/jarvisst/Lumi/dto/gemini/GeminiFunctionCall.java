package com.jarvisst.Lumi.dto.gemini;

import java.util.Map;

public record GeminiFunctionCall(String name, Map<String, Object> args) {
}
