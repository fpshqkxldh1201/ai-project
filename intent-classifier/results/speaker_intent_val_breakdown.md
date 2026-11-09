# 상대 발화 의도 검증셋 분해

- 문장 72개 · 정확도 **0.764** (95% CI 0.667–0.861) · macro-F1 **0.000** (95% CI 0.000–0.000)
- 신뢰구간은 문장 복원추출(부트스트랩 2000회). 표본이 작을수록 넓어집니다.

## 라벨별

| 값 | 문장 수 | 정확도 |
|---|---:|---:|
| apologize | 6 | 1.000 |
| ask_info | 6 | 1.000 |
| care | 6 | 1.000 |
| check_in | 6 | 0.667 |
| complain | 6 | 0.500 |
| criticize | 6 | 0.667 |
| decline | 6 | 0.667 |
| inform | 6 | 0.500 |
| invite | 6 | 1.000 |
| praise | 6 | 0.833 |
| request | 6 | 0.667 |
| urge | 6 | 0.667 |

## 자주 틀리는 쌍 (정답 → 예측)

| 정답 | 예측 | 횟수 |
|---|---|---:|
| complain | decline | 2 |
| decline | inform | 2 |
| urge | ask_info | 2 |
| inform | decline | 2 |
| request | decline | 1 |
| request | inform | 1 |
| check_in | ask_info | 1 |
| check_in | invite | 1 |