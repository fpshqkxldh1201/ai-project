"""문장별로 감정 모델의 확률 상위 5개와 중립 판정 기준을 보여준다(점검용).
사용: python scripts\\probe.py                       (기본 문장들)
      python scripts\\probe.py "오늘 너무 힘들었어" "일정 알려줘"
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

from emotion_labels import LABELS, NONE_IDX, summarize_with_gate  # noqa: E402
from emotion_model import Gate, load_model, predict_probs_and_gate  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
model_dir = Path(os.environ.get("EMOTION_MODEL_DIR", ROOT / "models" / "emotion"))
model, meta = load_model(model_dir)
nb = meta.get("neutral_below", 0.30)
gate = Gate.load(model_dir)
print(f"중립 판정 기준(neutral_below) = {nb}  (가장 높은 정서 확률이 이 값보다 낮으면 '중립')")
print(f"명령 게이트 기준 = {gate.threshold if gate else '없음'}  (명령일 확률이 이 값 이상이면 감정을 읽지 않고 중립)\n")

texts = sys.argv[1:] or [
    "오늘 너무 힘들었어", "시험 합격했어! 너무 기뻐", "일정 알려줘", "내일 3시에 회의 있어?",
    "아 진짜 짜증나", "고마워 덕분에 살았어", "오늘 날씨 어때?", "너무 피곤하다",
]
_P, _G = predict_probs_and_gate(model, gate, texts)
for k, (t, p) in enumerate(zip(texts, _P)):
    order = sorted(range(len(p)), key=lambda i: float(p[i]), reverse=True)
    top5 = [(LABELS[i], round(float(p[i]), 3)) for i in order if i != NONE_IDX][:5]
    gp = None if _G is None else float(_G[k])
    s = summarize_with_gate(p, nb, gp, None if gate is None else gate.threshold)
    print(f"{t}\n  -> {s['primary_emotion']} (mood {s['mood']}, valence {s['valence']}, conf {s['confidence']})")
    print(f"  상위5: {top5}  | '없음' 확률 {round(float(p[NONE_IDX]), 3)} | 명령 확률 {None if gp is None else round(gp, 3)}\n")
