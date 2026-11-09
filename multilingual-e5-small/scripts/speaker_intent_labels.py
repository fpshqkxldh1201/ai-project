"""상대방이 한 말의 '의도' 라벨과 관계 표기 (학습: train_speaker_intent.py, 서버: serve.py가 함께 쓴다).

Lumi 사용자에게 여자친구·부모님·친구·상사·동료 등이 메시지나 직접 한 말을 알려 주면, 그 말이 무엇을 바라는 말인지
12가지 의도 중 하나로 분류해 돌려준다. 라벨의 한국어 이름·설명·대응 팁은 Spring 쪽(SpeakerIntentService)이 가진다.
"""
from __future__ import annotations

LABELS = [
    "request",     # 부탁·요청·지시
    "ask_info",    # 정보·사실 확인 질문
    "check_in",    # 안부·근황·식사·귀가 확인
    "invite",      # 제안·초대·약속 잡기
    "complain",    # 서운함·불만 표현
    "care",        # 걱정·챙김·응원·애정
    "praise",      # 칭찬·감사
    "criticize",   # 지적·꾸중·비판
    "apologize",   # 사과·해명
    "decline",     # 거절·보류·미룸
    "urge",        # 재촉·독촉·압박
    "inform",      # 단순 통보·공유
]
N_LABELS = len(LABELS)

# API에서 받는 관계 키 → 학습 데이터에 쓴 한국어 표기
RELATIONS = {
    "parent": "부모님",
    "partner": "연인",
    "friend": "친구",
    "boss": "상사",
    "colleague": "동료",
    "other": "기타",
}
RELATION_VALUES = set(RELATIONS.values())


def normalize_relation(value) -> str:
    """관계 키(parent 등)나 한국어 표기 → 한국어 표기. 모르는 값은 '기타'."""
    v = str(value or "").strip()
    if v in RELATIONS:
        return RELATIONS[v]
    return v if v in RELATION_VALUES else "기타"


def model_text(relation: str, text: str, mode: str = "prefix") -> str:
    """인코더에 넣는 글. 같은 말도 누가 했느냐에 따라 뜻이 달라질 수 있어(예: '밥 먹었어?') 관계를 앞에 붙인다."""
    text = " ".join(str(text or "").split())
    return text if mode == "none" else f"{normalize_relation(relation)}: {text}"
