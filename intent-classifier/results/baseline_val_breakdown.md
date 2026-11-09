# 기준선 검증셋 분해

- 문장 116개 · 정확도 **0.862** (95% CI 0.802–0.922) · macro-F1 **0.861** (95% CI 0.790–0.915)
- 신뢰구간은 문장 복원추출(부트스트랩 2000회). 표본이 작을수록 넓어집니다.

## 언어별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| en | 32 | 0.719 |
| ko | 84 | 0.917 |

## 출처별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| ai | 83 | 0.880 |
| massive | 33 | 0.818 |

## hard 여부별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| 0 | 109 | 0.862 |
| 1 | 7 | 0.857 |

## 라벨별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| chat_emotion | 15 | 0.933 |
| note_delete | 10 | 0.900 |
| note_save | 10 | 0.900 |
| reminder_add | 14 | 1.000 |
| reminder_get | 13 | 0.769 |
| schedule_add | 14 | 0.857 |
| schedule_get | 14 | 0.857 |
| search_video | 13 | 0.692 |
| search_web | 13 | 0.846 |

## 자주 틀리는 쌍 (정답 → 예측)

| 정답 | 예측 | 횟수 |
|---|---|---:|
| reminder_get | reminder_add | 2 |
| search_video | chat_emotion | 2 |
| search_web | chat_emotion | 2 |
| note_save | note_delete | 1 |
| search_video | note_save | 1 |
| reminder_get | schedule_get | 1 |
| note_delete | note_save | 1 |
| schedule_add | schedule_get | 1 |