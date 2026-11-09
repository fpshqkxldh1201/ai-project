package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.entity.User;

/**
 * 서버가 먼저 말을 거는 고정 문구(리마인더·일정 임박·안부 알림)를 사용자의 말하는 언어로 만든다.
 * 한국어가 아니면 화면에만 보여줄 한국어 번역(translation)을 함께 돌려준다(번역은 음성으로 읽지 않는다).
 *
 * 언어 결정: "답변 언어"가 자동(auto)이면 사용자가 말하는 입력 언어를 따른다 — 그래야 영어로 말하는 사용자가
 * 자동 모드여도 알림이 영어로 나온다. 지원하지 않는 값이면 한국어.
 * 리마인더 내용·일정 제목은 사용자가 쓴 그대로라서 번역하지 않고 문구 틀만 번역한다.
 */
public final class NotificationText {

    private NotificationText() {
    }

    /** 말할 문구와, 외국어일 때 화면에만 보여줄 한국어 번역(한국어 문구면 translation=null). */
    public record Message(String text, String translation) {
    }

    /** 이 사용자에게 알림을 어떤 언어로 말할지(ko/en/zh/ja/es). */
    public static String languageOf(User user) {
        if (user == null) {
            return "ko";
        }
        String lang = user.getResponseLang();
        if ("auto".equals(lang)) {
            lang = user.getInputLang();
        }
        return switch (lang == null ? "" : lang) {
            case "en", "zh", "ja", "es" -> lang;
            default -> "ko";
        };
    }

    public static Message reminder(String lang, String content) {
        String ko = "리마인더예요: " + content;
        return switch (lang) {
            case "en" -> new Message("Reminder: " + content, ko);
            case "ja" -> new Message("リマインダーです：" + content, ko);
            case "zh" -> new Message("提醒：" + content, ko);
            case "es" -> new Message("Recordatorio: " + content, ko);
            default -> new Message(ko, null);
        };
    }

    public static Message upcomingSchedule(String lang, String time, String title) {
        String ko = "곧 " + time + "에 '" + title + "' 일정이 있어요.";
        return switch (lang) {
            case "en" -> new Message("Heads up, '" + title + "' starts at " + time + ".", ko);
            case "ja" -> new Message("まもなく" + time + "に「" + title + "」の予定があります。", ko);
            case "zh" -> new Message(time + "即将开始“" + title + "”。", ko);
            case "es" -> new Message("Atención, a las " + time + " tiene «" + title + "».", ko);
            default -> new Message(ko, null);
        };
    }

    /**
     * 부정적인 기분이 이어질 때의 안부. severe(매우 부정적)이면 주변·전문가에게 이야기해 보라는 문장을 덧붙인다.
     * 한국어 사용자에게만 한국 정신건강 위기상담 번호(1577-0199)를 안내한다(다른 언어 사용자는 지역이 다를 수 있음).
     */
    public static Message care(String lang, boolean severe) {
        String koBase = "요즘 기분이 계속 가라앉아 보여요. 잠깐 자리에서 일어나 물 한 잔 마시고 스트레칭 어때요? "
                + "얘기하고 싶으면 언제든 말해 주세요.";
        if ("ko".equals(lang)) {
            return new Message(koBase + (severe
                    ? " 많이 힘들면 가까운 사람이나 전문 상담(정신건강 위기상담 1577-0199)에도 이야기해 보세요." : ""), null);
        }
        String koTranslation = koBase + (severe ? " 많이 힘들면 가까운 사람이나 전문가에게도 이야기해 보세요." : "");
        String text = switch (lang) {
            case "ja" -> "最近ずっと気分が沈んでいるように見えます。少し席を立って、水を飲んでストレッチしませんか？"
                    + "話したくなったらいつでも声をかけてくださいね。"
                    + (severe ? "とてもつらいときは、身近な人や専門家にも相談してみてください。" : "");
            case "zh" -> "你最近好像一直情绪低落。要不要起来喝杯水、伸展一下？想聊聊的时候随时告诉我。"
                    + (severe ? "如果实在太难受，也请和身边的人或专业人士聊一聊。" : "");
            case "es" -> "Llevas un rato con el ánimo bajo. ¿Qué tal levantarte, beber un poco de agua y estirarte? "
                    + "Cuando quieras hablar, aquí estoy."
                    + (severe ? " Si se vuelve demasiado, habla con alguien cercano o con un profesional." : "");
            default -> "You've seemed a bit down for a while now. Maybe step away for a few minutes, "
                    + "drink some water, stretch. I'm here whenever you want to talk."
                    + (severe ? " If it feels like too much, please talk to someone close to you or a professional." : "");
        };
        return new Message(text, koTranslation);
    }
}
