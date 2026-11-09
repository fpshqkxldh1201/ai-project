"""감정 라벨 표와 결과 요약 규칙 (Lumi가 직접 설계한 부분).

KOTE의 44개 정서 라벨(인덱스 순서는 KOTE 저장소의 라벨 목록과 같다)마다
  - valence(긍정도, -1~1), arousal(활성도, -1~1)
  - mood: Lumi의 답변 톤 정책(EmotionTonePolicy)이 쓰는 9가지 기분 중 하나
를 정해 둔다. valence/arousal 값은 러셀의 감정 원형 모델(Russell circumplex: 기분의 좋고 나쁨 × 에너지의
높고 낮음)을 참고해 직접 정한 값이며, 데이터로 학습한 값이 아니다.

모델은 문장마다 44개 정서의 확률을 내고(multi-label), summarize()가 그 확률을
  primary/secondary 감정, mood, valence, arousal, confidence 로 줄여 Lumi 서버(Spring)에 돌려준다.
"""
from __future__ import annotations

NONE_LABEL = "없음"

# (라벨, valence, arousal, mood)  — 인덱스 = 줄 순서 (KOTE와 동일)
_TABLE = [
    ("불평/불만", -0.6, 0.4, "angry"),
    ("환영/호의", 0.6, 0.3, "happy"),
    ("감동/감탄", 0.7, 0.5, "happy"),
    ("지긋지긋", -0.6, 0.2, "angry"),
    ("고마움", 0.7, 0.1, "happy"),
    ("슬픔", -0.7, -0.4, "sad"),
    ("화남/분노", -0.8, 0.8, "angry"),
    ("존경", 0.6, 0.2, "happy"),
    ("기대감", 0.5, 0.6, "energetic"),
    ("우쭐댐/무시함", -0.2, 0.3, "neutral"),
    ("안타까움/실망", -0.5, -0.2, "sad"),
    ("비장함", 0.0, 0.6, "neutral"),
    ("의심/불신", -0.4, 0.3, "neutral"),
    ("뿌듯함", 0.8, 0.4, "happy"),
    ("편안/쾌적", 0.7, -0.6, "calm"),
    ("신기함/관심", 0.4, 0.5, "energetic"),
    ("아껴주는", 0.7, -0.1, "calm"),
    ("부끄러움", -0.3, 0.4, "neutral"),
    ("공포/무서움", -0.8, 0.8, "sad"),
    ("절망", -0.9, -0.4, "melancholy"),
    ("한심함", -0.6, 0.1, "neutral"),
    ("역겨움/징그러움", -0.7, 0.4, "angry"),
    ("짜증", -0.7, 0.6, "angry"),
    ("어이없음", -0.4, 0.4, "neutral"),
    ("없음", 0.0, 0.0, "neutral"),
    ("패배/자기혐오", -0.8, -0.5, "melancholy"),
    ("귀찮음", -0.3, -0.6, "neutral"),
    ("힘듦/지침", -0.6, -0.7, "melancholy"),
    ("즐거움/신남", 0.8, 0.8, "energetic"),
    ("깨달음", 0.3, 0.2, "neutral"),
    ("죄책감", -0.7, -0.1, "sad"),
    ("증오/혐오", -0.9, 0.7, "angry"),
    ("흐뭇함(귀여움/예쁨)", 0.7, 0.0, "happy"),
    ("당황/난처", -0.3, 0.6, "neutral"),
    ("경악", -0.5, 0.9, "neutral"),
    ("부담/안_내킴", -0.4, 0.0, "neutral"),
    ("서러움", -0.7, -0.2, "sad"),
    ("재미없음", -0.3, -0.4, "neutral"),
    ("불쌍함/연민", -0.3, -0.3, "sad"),
    ("놀람", 0.1, 0.8, "neutral"),
    ("행복", 0.9, 0.5, "happy"),
    ("불안/걱정", -0.6, 0.5, "sad"),
    ("기쁨", 0.8, 0.6, "happy"),
    ("안심/신뢰", 0.6, -0.5, "calm"),
]

LABELS = [t[0] for t in _TABLE]
VALENCE = [t[1] for t in _TABLE]
AROUSAL = [t[2] for t in _TABLE]
MOOD = [t[3] for t in _TABLE]
NONE_IDX = LABELS.index(NONE_LABEL)
N_LABELS = len(LABELS)

# 화면 배지에 보일 중립 이름('없음'은 사용자에게 어색하다)
NEUTRAL_DISPLAY = "중립"

# summarize() 규칙 — 검증셋(KOTE val)으로만 조정한다. 평가셋과 Lumi 점검 문장으로 맞추지 않는다.
ACTIVE_MIN = 0.25        # 이 확률 이상인 정서만 valence/arousal 계산에 참여
NEUTRAL_BELOW = 0.30     # 기본값(최소값). 실제 값은 train.py가 검증셋에서 정해 meta.json에 저장한다
NONE_WINS_AT = 0.50      # '없음' 확률이 이 이상이면 '중립' (명령·질문처럼 감정 표현이 없는 문장. 다른 정서 확률이 높아도 '없음'이 절반을 넘으면 중립)
NEUTRAL_MAX = 0.50       # 중립 기준(neutral_below)의 상한. 검증셋에서 정한 값이 이보다 높아도 '확률이 절반을 넘으면 감정'으로 본다
# 글만으로 판단하기 어려운 문장 표시: 1등 감정 확률이 AMBIG_TOP보다 낮거나, 중립이지만 AMBIG_MIN 이상의 감정 신호가 남아 있을 때
AMBIG_TOP = 0.45
AMBIG_MIN = 0.12


def neutral_summary(confidence: float, ambiguous: bool = False) -> dict:
    """감정이 없는 문장(명령·요청·질문 등)의 결과. ambiguous=True면 '감정이 없다'가 아니라 '글만으로는 판단이 어렵다'는 뜻."""
    return {
        "primary_emotion": NEUTRAL_DISPLAY,
        "secondary_emotion": None,
        "mood": "neutral",
        "valence": 0.0,
        "arousal": 0.0,
        "confidence": round(float(confidence), 4),
        "ambiguous": bool(ambiguous),
    }


def summarize_with_gate(probs, neutral_below: float = NEUTRAL_BELOW, gate_prob=None, gate_threshold=None) -> dict:
    """명령 게이트(gate_prob >= gate_threshold)가 '명령·요청 문장'이라고 판단하면 정서 확률과 상관없이 중립으로 돌려준다.
    게이트가 없으면(None) summarize()와 같다."""
    if gate_prob is not None and gate_threshold is not None and float(gate_prob) >= float(gate_threshold):
        return neutral_summary(gate_prob)
    return summarize(probs, neutral_below)


def summarize(probs, neutral_below: float = NEUTRAL_BELOW) -> dict:
    """44개 정서 확률(0~1) → Lumi 서버로 보낼 요약.

    neutral_below: 가장 높은 정서 확률이 이 값보다 낮으면 중립. 학습 때 검증셋으로 정해 meta.json에 저장한 값을 쓴다.

    반환: primary_emotion, secondary_emotion(없으면 None), mood, valence, arousal, confidence
    """
    neutral_below = min(neutral_below, NEUTRAL_MAX)
    p = [float(x) for x in probs]
    if len(p) != N_LABELS:
        raise ValueError(f"확률 {N_LABELS}개가 필요합니다 (받은 값 {len(p)}개)")
    order = sorted((i for i in range(N_LABELS) if i != NONE_IDX), key=lambda i: p[i], reverse=True)
    top = order[0]
    p_top = p[top]
    if p_top < neutral_below or p[NONE_IDX] >= NONE_WINS_AT:
        # 중립일 때의 확신도 = '감정이 없다'는 쪽의 확률(높을수록 확실히 평범한 문장)
        # 감정이 약한데 '감정 없음'이라고도 확신하지 못하면 글만으로는 판단이 어려운 문장 → 사진을 청할 수 있다
        return neutral_summary(max(p[NONE_IDX], 1.0 - p_top), ambiguous=(p[NONE_IDX] < NONE_WINS_AT and p_top >= AMBIG_MIN))
    active = [i for i in order if p[i] >= ACTIVE_MIN]
    wsum = sum(p[i] for i in active)
    valence = sum(p[i] * VALENCE[i] for i in active) / wsum
    arousal = sum(p[i] * AROUSAL[i] for i in active) / wsum
    second = order[1] if len(order) > 1 and p[order[1]] >= ACTIVE_MIN else None
    return {
        "primary_emotion": LABELS[top],
        "secondary_emotion": LABELS[second] if second is not None else None,
        "mood": MOOD[top],
        "valence": round(max(-1.0, min(1.0, valence)), 4),
        "arousal": round(max(-1.0, min(1.0, arousal)), 4),
        "confidence": round(p_top, 4),
        "ambiguous": bool(p_top < AMBIG_TOP),
    }


def target_valence(label_ids) -> float | None:
    """정답 라벨 집합의 평균 valence ('없음' 제외). 정답에 감정이 없으면 None."""
    ids = [i for i in label_ids if i != NONE_IDX]
    if not ids:
        return None
    return sum(VALENCE[i] for i in ids) / len(ids)


def polarity(v: float, margin: float = 0.2) -> str:
    return "pos" if v >= margin else "neg" if v <= -margin else "neu"
