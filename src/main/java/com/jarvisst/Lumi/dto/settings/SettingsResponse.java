package com.jarvisst.Lumi.dto.settings;

public record SettingsResponse(
        String inputLang,
        String responseLang,
        String voiceGender,
        boolean ttsEnabled,
        boolean alwaysListening,
        boolean emotionLogEnabled,
        boolean proactiveCareEnabled
) {
}
