"""공통 도구: 라벨 목록, CSV 읽기, 문장 정규화, 유사도(문자 3-gram Jaccard)."""
import csv
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
SPLITS = DATA / "splits"
RESULTS = ROOT / "results"

# v2부터 열이 늘었다. 예전 파일(열이 부족한 CSV)도 읽을 수 있게 read_rows가 기본값을 채워 준다.
FIELDS = ("id", "text", "label", "lang", "hard", "source", "version")
FIELD_DEFAULTS = {"source": "ai", "version": "v1"}
SOURCES = ("ai", "peer", "self", "massive")  # AI 초안 / 급우 제출 / 직접 작성 / 공개 데이터 MASSIVE(한국어)
VERSIONS = ("v1", "v1.1", "v2", "v3")
FROZEN_TEST = SPLITS / "test_v1_frozen.csv"   # 한 번 열어 본 테스트셋. 이후 수정·재분할 금지.

# OOS(범위 밖 요청) 범주 — docs/label_guide.md §OOS 참고
OOS_CATEGORIES = {
    "O1": "발신·연락(문자·전화·메일 보내기)",
    "O2": "기기·스마트홈·재생 제어",
    "O3": "결제·주문·예약",
    "O4": "파일·시스템 조작",
    "O5": "기존 일정·알림 수정·삭제·취소 (near)",
    "O6": "조건·이벤트 기반 자동화 (near)",
    "O7": "계정·설정 변경",
}
OOS_FIELDS = ("id", "text", "category", "lang", "near", "source", "version")

LABELS = [
    "schedule_add", "schedule_get",
    "reminder_add", "reminder_get",
    "note_save", "note_delete",
    "search_web", "search_video",
    "chat_emotion",
]


def read_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = [{k: (v or "").strip() for k, v in r.items() if k is not None} for r in csv.DictReader(f)]
    for r in rows:
        for k, v in FIELD_DEFAULTS.items():
            if "label" in r:           # 의도 문장 파일에만 기본값 적용(OOS 파일은 제외)
                r.setdefault(k, v)
    return rows


def write_rows(path, rows, fields=FIELDS):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(fields), extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def normalize(text):
    """중복 판정용: 소문자 + 글자·숫자만."""
    return re.sub(r"[\W_]+", "", text.lower())


def grams(text, n=3):
    s = normalize(text)
    if len(s) < n:
        return {s}
    return {s[i:i + n] for i in range(len(s) - n + 1)}


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def similar_pairs(rows, threshold):
    """문장 쌍 중 유사도가 threshold 이상인 것: [(i, j, score)]"""
    g = [grams(r["text"]) for r in rows]
    out = []
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            s = jaccard(g[i], g[j])
            if s >= threshold:
                out.append((i, j, s))
    return out
