# Lumi 의도 분류기 (직접 학습) — 1단계: 라벨·데이터·기준선

```
intent-classifier/
├─ data/intents.csv          ← 문장 290개 (id,text,label,lang,hard)  ★ 여기를 검수하세요
├─ data/splits/              ← train/val/test (split.py가 생성)
├─ docs/label_guide.md       ← 라벨 정의서와 애매할 때 쓰는 규칙
├─ scripts/                  ← validate.py · split.py · baseline.py
└─ results/                  ← 기준선 결과(검증셋만, 테스트셋은 잠금)
```

## 1. 검수 (사람이 할 일, 약 15~20분)
1. `data/intents.csv`를 엑셀로 엽니다(UTF-8, 한글 안 깨짐).
2. `docs/label_guide.md`를 읽고 **`label` 열이 맞는지** 한 줄씩 봅니다. 틀린 건 라벨 이름을 직접 고칩니다. 애매하면 `hard`를 1로.
3. 말이 부자연스러운 문장은 고쳐 쓰거나 지우고, **본인이 실제로 쓸 법한 말투**의 문장으로 몇 개 바꿔 주세요(학습 데이터가 내 말투에 가까울수록 좋습니다).
4. 저장할 때 형식은 **CSV UTF-8**을 유지합니다.

## 2. 실행 (Python 3.10+)
```bash
pip install -r requirements.txt
cd scripts
python validate.py      # 라벨 오타·중복·불균형 점검 (오류가 0이어야 다음 단계로)
python split.py         # 70/15/15 분할 (비슷한 문장은 한쪽으로만 → 누수 방지)
python baseline.py      # 기준선: 글자 n-gram TF-IDF + 로지스틱 회귀, 검증셋 결과
```
`python baseline.py --final`은 **테스트셋을 여는 명령**입니다. 모델·데이터를 다 확정한 뒤 한 번만 실행하세요(실행 기록은 `results/final_log.txt`에 남습니다).

## 3. 현재 기준선 (검수 전 초안 기준 — 참고용)
- 검증 정확도 약 0.69 (45문장), 학습 문장이 늘수록 계속 오르는 학습곡선 → **데이터를 더 다듬고 늘리면 개선 여지가 큼**.
- 어려운 쪽: `schedule_get`↔`schedule_add`, `reminder_add`↔`reminder_get`, 영어 문장.
- 검증셋이 45개뿐이라 한 문장이 약 2.2%p입니다. 수치는 "대략"으로만 해석하세요.

## 4. 다음 단계
신경망 학습(다국어 문장 임베딩 + 분류기, loss/accuracy 곡선) → confusion matrix 평가 → Lumi 연동.
