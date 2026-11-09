# head 검증셋 분해 (seed 0)

- 문장 116개 · 정확도 **0.888** (95% CI 0.828–0.940) · macro-F1 **0.893** (95% CI 0.830–0.944)
- 신뢰구간은 문장 복원추출(부트스트랩 2000회). 표본이 작을수록 넓어집니다.

## 언어별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| en | 32 | 0.938 |
| ko | 84 | 0.869 |

## 출처별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| ai | 83 | 0.904 |
| massive | 33 | 0.848 |

## hard 여부별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| 0 | 109 | 0.890 |
| 1 | 7 | 0.857 |

## 라벨별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| chat_emotion | 15 | 0.933 |
| note_delete | 10 | 1.000 |
| note_save | 10 | 1.000 |
| reminder_add | 14 | 0.929 |
| reminder_get | 13 | 0.846 |
| schedule_add | 14 | 0.929 |
| schedule_get | 14 | 0.643 |
| search_video | 13 | 1.000 |
| search_web | 13 | 0.769 |

## 자주 틀리는 쌍 (정답 → 예측)

| 정답 | 예측 | 횟수 |
|---|---|---:|
| schedule_get | reminder_get | 2 |
| schedule_get | schedule_add | 2 |
| schedule_get | chat_emotion | 1 |
| schedule_add | reminder_add | 1 |
| reminder_add | schedule_add | 1 |
| reminder_get | schedule_get | 1 |
| search_web | schedule_add | 1 |
| reminder_get | reminder_add | 1 |