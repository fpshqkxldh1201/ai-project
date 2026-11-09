package com.jarvisst.Lumi.service;

import com.jarvisst.Lumi.dto.emotion.EmotionResult;
import com.jarvisst.Lumi.security.EmotionSanitizer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 감정 분석 모델(multilingual-e5-small)의 결과를 "어떻게 답할지"로 바꾸는 규칙. (모델이 얼마나 정확한지는
 * multilingual-e5-small 쪽 일이고, 여기서는 받은 값을 어떻게 쓸지만 정한다.)
 *
 *  - 서버가 보내 준 기분(mood)이 알려진 값이면 그대로 쓰고, 아니면 감정 이름으로, 그것도 모르면 valence/arousal 사분면으로 정한다.
 *    프롬프트에는 원문 라벨이 아니라 정규화된 값만 넣는다(외부 값이 지시문으로 섞이는 것 방지).
 *  - 확신도가 낮으면 반영을 줄이거나(LIGHT) 아예 하지 않는다(NONE). 틀린 분석으로 어색하게 위로하지 않기 위해서다.
 *  - 지침은 페르소나(담담한 곁의 존재감, 과장·감탄 금지)를 깨지 않는 범위의 톤 조절이다.
 *  - 최근 감정 흐름(EmotionTrendStore)에 따라 한 번 더 조정한다.
 */
@Component
public class EmotionTonePolicy {

    public enum Mood { HAPPY, ENERGETIC, CALM, SAD, ANGRY, DREAMY, ROMANTIC, MELANCHOLY, NEUTRAL }

    public enum Level { NONE, LIGHT, FULL }

    /** 정규화된 판독 결과. */
    public record Reading(Mood mood, Level level, double valence, double arousal, double confidence) {

        public String toneLabel() {
            if (level == Level.NONE) {
                return null;
            }
            String base = switch (mood) {
                case SAD, MELANCHOLY -> "공감 먼저, 차분하게";
                case ANGRY -> "인정 먼저, 간결하게";
                case HAPPY -> "함께 기뻐하는 톤";
                case ENERGETIC -> "경쾌하게";
                case CALM -> "차분하게";
                case DREAMY -> "여유 있게";
                case ROMANTIC -> "가볍게 받아주는 톤";
                case NEUTRAL -> null;
            };
            if (base == null) {
                return null;
            }
            return level == Level.LIGHT ? base + " (약하게)" : base;
        }
    }

    static final double LIGHT_MIN = 0.30;
    static final double FULL_MIN = 0.55;

    private static final Map<String, Mood> ALIASES = Map.ofEntries(
            Map.entry("happy", Mood.HAPPY), Map.entry("행복", Mood.HAPPY), Map.entry("기쁨", Mood.HAPPY), Map.entry("즐거움", Mood.HAPPY),
            Map.entry("energetic", Mood.ENERGETIC), Map.entry("활기참", Mood.ENERGETIC), Map.entry("활기", Mood.ENERGETIC), Map.entry("신남", Mood.ENERGETIC),
            Map.entry("calm", Mood.CALM), Map.entry("차분함", Mood.CALM), Map.entry("차분", Mood.CALM), Map.entry("평온", Mood.CALM),
            Map.entry("sad", Mood.SAD), Map.entry("슬픔", Mood.SAD), Map.entry("슬픈", Mood.SAD),
            Map.entry("angry", Mood.ANGRY), Map.entry("화남", Mood.ANGRY), Map.entry("분노", Mood.ANGRY), Map.entry("짜증", Mood.ANGRY),
            Map.entry("dreamy", Mood.DREAMY), Map.entry("몽환적", Mood.DREAMY), Map.entry("몽환", Mood.DREAMY),
            Map.entry("romantic", Mood.ROMANTIC), Map.entry("설렘", Mood.ROMANTIC), Map.entry("로맨틱", Mood.ROMANTIC),
            Map.entry("melancholy", Mood.MELANCHOLY), Map.entry("우울", Mood.MELANCHOLY), Map.entry("쓸쓸함", Mood.MELANCHOLY),
            Map.entry("neutral", Mood.NEUTRAL), Map.entry("중립", Mood.NEUTRAL));

    /** 감정 분석 결과를 정규화한다. 결과가 없으면 null. */
    public Reading read(EmotionResult raw) {
        if (raw == null) {
            return null;
        }
        double valence = EmotionSanitizer.clamp(raw.valence(), -1.0, 1.0);
        double arousal = EmotionSanitizer.clamp(raw.arousal(), -1.0, 1.0);
        double confidence = EmotionSanitizer.clamp(raw.confidence(), 0.0, 1.0);
        Mood mood = moodOf(raw.mood());
        if (mood == null) {
            mood = moodOf(raw.primaryEmotion());
        }
        if (mood == null) {
            mood = fromQuadrant(valence, arousal);
        }
        Level level = confidence >= FULL_MIN ? Level.FULL : confidence >= LIGHT_MIN ? Level.LIGHT : Level.NONE;
        return new Reading(mood, level, valence, arousal, confidence);
    }

    /** 이름(영어/한국어)으로 기분을 찾는다. 모르는 이름이면 null. 외부 값이라 먼저 걸러서 쓴다. */
    private static Mood moodOf(String name) {
        String label = EmotionSanitizer.label(name);
        return label == null ? null : ALIASES.get(label.toLowerCase(Locale.ROOT));
    }

    /** 이름을 모를 때: 기분의 방향(valence)과 에너지(arousal)로 가장 가까운 감정을 고른다. */
    static Mood fromQuadrant(double valence, double arousal) {
        if (valence >= 0.2) {
            return arousal >= 0.2 ? Mood.ENERGETIC : Mood.CALM;
        }
        if (valence <= -0.2) {
            return arousal >= 0.2 ? Mood.ANGRY : Mood.SAD;
        }
        return Mood.NEUTRAL;
    }

    /** Gemini 프롬프트에 붙일 내부 힌트. 반영하지 않을 때는 null. */
    public String buildHint(Reading r, EmotionTrendStore.Trend trend, boolean fromImage) {
        if (r == null || r.level() == Level.NONE) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[내부 감정분석 — 참고만 하고 사용자에게 언급하지 마세요] ");
        sb.append(String.format(Locale.ROOT, "주감정=%s, valence=%.2f, arousal=%.2f, 확신도=%.0f%%. ",
                r.mood().name(), r.valence(), r.arousal(), r.confidence() * 100));
        if (fromImage) {
            sb.append("이 감정은 사용자가 보낸 사진의 표정·분위기에서 읽은 것입니다. ");
        }
        if (r.level() == Level.LIGHT) {
            sb.append("분석 확신이 낮습니다. 감정을 단정하지 말고 사용자가 말한 내용 자체를 우선하며, 아래 톤은 아주 살짝만 반영하세요. ");
        }
        sb.append("응답 지침: ").append(guidance(r.mood())).append(' ');
        if (r.arousal() <= -0.3) {
            sb.append("에너지가 낮으니 문장을 짧게, 천천히 말하세요. ");
        } else if (r.arousal() >= 0.4) {
            sb.append("에너지가 높으니 핵심부터 빠르게 답하세요. ");
        }
        if (r.valence() <= -0.5 && r.level() == Level.FULL) {
            sb.append("농담과 가벼운 말투는 자제하세요. ");
            if (r.valence() <= -0.7) {
                sb.append("스스로를 해치려는 듯한 말이 보이면 담담하게 걱정을 표현하고 가까운 사람이나 전문 상담에 도움을 구하도록 권하세요. ");
            }
        }
        if (trend == EmotionTrendStore.Trend.NEGATIVE_STREAK) {
            sb.append("부정적인 기분이 여러 번 이어지고 있습니다. 해결책을 늘어놓기보다 상태를 한 번 묻거나, 잠깐 쉬어가자는 제안을 "
                    + "한 번만 자연스럽게 하세요(이미 했다면 반복하지 마세요). ");
        } else if (trend == EmotionTrendStore.Trend.RECOVERING) {
            sb.append("직전보다 기분이 나아진 듯합니다. 가볍게 이어가되 들뜬 어조는 피하세요. ");
        }
        sb.append("어떤 경우에도 페르소나(담담한 어조, 감탄사·과장 금지)는 유지합니다.");
        return sb.toString();
    }

    private static String guidance(Mood mood) {
        return switch (mood) {
            case SAD, MELANCHOLY -> "조언이나 해결책보다 먼저 지금 상태를 짧게 알아주세요(한 문장). 낮고 부드러운 말투, 짧은 답변, 질문은 최대 하나. 할 일이나 일정을 재촉하지 마세요.";
            case ANGRY -> "반박·훈계·분석을 하지 마세요. 힘든 상황임을 먼저 인정하고, 짧고 단정한 문장으로 답하세요. 해결책은 원할 때 제안하세요.";
            case HAPPY -> "기쁜 일을 함께 반기되 과장 없이 구체적으로 받아주세요. 자연스러우면 다음 행동을 가볍게 제안해도 됩니다.";
            case ENERGETIC -> "반응을 경쾌하고 빠르게, 군더더기 없이 이어가세요.";
            case CALM -> "군더더기 없이 차분하게, 필요한 정보 중심으로 답하세요.";
            case DREAMY -> "서두르지 않는 여유 있는 문장으로 답하되, 비유는 과하지 않게 하세요.";
            case ROMANTIC -> "설레는 이야기를 가볍게 받아주되 사적인 부분을 캐묻지 마세요.";
            case NEUTRAL -> "기본 톤을 유지하세요.";
        };
    }
}
