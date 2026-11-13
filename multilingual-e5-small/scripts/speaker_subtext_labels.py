"""상대방 말의 '속뜻' 라벨 (학습: train_speaker_subtext.py, 서버: serve.py가 함께 쓴다).

겉뜻(12개 의도, speaker_intent_labels.py)과 따로, 말 뒤에 깔린 마음을 8가지 중 하나로 가른다.
라벨의 한국어 이름·설명·대응 팁은 Spring 쪽(SpeakerIntentService)이 고정 문구로 가진다.
"""
from __future__ import annotations

from speaker_intent_labels import RELATION_VALUES, RELATIONS, model_text, normalize_relation  # noqa: F401  (관계 표기는 겉뜻 분류기와 같다)

LABELS = [
    "none",        # 속뜻 없음: 말 그대로
    "attention",   # 관심·연락·함께 있기를 바람
    "hurt_hint",   # 서운함을 돌려 말함
    "probe",       # 떠보기·확인받고 싶음
    "sarcasm",     # 반어·비꼼
    "pressure",    # 압박·재촉을 돌려 말함
    "worry_hint",  # 걱정·챙김을 돌려 말함
    "soft_no",     # 거절·싫음을 돌려 말함
]
N_LABELS = len(LABELS)
