# 2단계: 신경망 학습 (직접 학습 + 평가)

## 무엇을 학습하나
- **인코더**: `intfloat/multilingual-e5-small` (다국어 문장 임베딩, 약 118M 파라미터, 한국어·영어 지원). 문장을 384차원 벡터로 바꿉니다.
- **분류기(헤드)**: `Linear(384→128) → GELU → Dropout(0.3) → Linear(128→9)`. **이 부분은 처음부터 직접 학습**합니다.
- 두 가지 방식을 비교합니다.
  | 방식 | 명령 | 학습되는 것 | 시간(CPU) |
  |---|---|---|---|
  | 헤드 학습(기본) | `python train_nn.py` | 분류기만 (인코더 고정) | 수십 초 |
  | 미세조정 | `python train_nn.py --finetune` | 인코더 + 분류기 | 수 분 |

## 학습 설정
- 손실: Cross-Entropy, 옵티마이저: AdamW(weight decay 0.01), 배치 32
- 헤드 학습: lr 3e-3, 최대 300에폭, **검증 손실이 25에폭 개선 없으면 조기 종료**, 가장 좋았던 에폭의 가중치 사용
- 미세조정: 헤드 lr 1e-3 / 인코더 lr 3e-5, warm-up 10% 후 선형 감소, 최대 6에폭(조기 종료 patience 2)
- 데이터가 작아서 결과가 시드에 따라 흔들리므로 **헤드 학습은 5개 시드**의 평균±표준편차로 보고합니다.

## 실행 순서
```
cd intent-classifier
python -m pip install -r requirements.txt        # torch, transformers 포함 (처음 한 번)
cd scripts
python train_nn.py                               # ① 헤드 학습 (처음엔 모델 내려받기 ≈ 470MB)
python train_nn.py --finetune                    # ② 미세조정 (선택, 시간 여유 있을 때)
python train_nn.py --final                       # ③ 마지막에 한 번만: 테스트셋 평가
```
`--final`은 검증으로 정한 에폭 수로 학습+검증 데이터를 합쳐 다시 학습한 뒤 테스트셋을 **한 번** 평가합니다. 기록은 `results/final_log.txt`에 남습니다.

## 결과물 (`results/`)
| 파일 | 내용 |
|---|---|
| `nn_head_curves.png` | 에폭별 **loss / accuracy 곡선**(train vs validation, 시드 5개) |
| `nn_head_history.csv` | 에폭별 수치(발표 자료용) |
| `nn_head_val_confusion.png` | **confusion matrix** |
| `nn_head_val_report.txt` | 클래스별 정밀도·재현율·F1 |
| `nn_head_val_errors.md` | 틀린 문장 목록(오류 분석용) |
| `compare_val_head.md` | 기준선 vs 신경망 비교표 |
(미세조정은 파일 이름이 `nn_finetune_*`)

## 결과를 읽는 법
- train loss만 계속 내려가고 validation loss가 올라가면 **과적합** → 조기 종료가 막아 준 지점이 best epoch.
- 검증셋이 45문장이라 한 문장이 ≈2.2%p입니다. 기준선과 몇 %p 차이는 오차 범위일 수 있어 평균±표준편차와 오분류 문장으로 함께 판단하세요.
- `models/`에는 학습된 가중치가 저장됩니다(헤드는 수백 KB). 미세조정 가중치는 수백 MB라 `.gitignore`로 커밋에서 제외했습니다.
