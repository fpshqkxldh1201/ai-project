# 상대방 발화 의도 분류기 결과 — speaker-intent-e5small-lr-20261109-1613

- 인코더 multilingual-e5-small(고정) + 소프트맥스 회귀(C=20.0, 확률 보정 온도 T=0.3) / 관계 표기: prefix / 문장 360개 (학습 216, 검증 72, 테스트 72)
- 데이터는 AI가 쓴 문장이며 의도당 30개로 적다. 아래 수치는 '같은 방식으로 쓴 문장'에서의 값이라 실제 말투에서는 낮아질 수 있다.

## 검증셋

| 지표 | 값 |
|---|---|
| 정확도 | 0.764 (95% 구간 0.667~0.861, 72문장) |
| macro-F1 | 0.770 |
| 정답이 상위 2개 안에 있음 | 0.875 |
| 기준선(글자 n-gram TF-IDF + 회귀) 정확도 | 0.639 |

애매함 기준 tau=0.45: 검증셋에서 답하는 비율 0.89, 답한 문장의 정확도 0.81.

### 의도별 재현율(검증)

| 의도 | 문장 수 | 재현율 |
|---|---|---|
| request | 6 | 0.67 |
| ask_info | 6 | 1.00 |
| check_in | 6 | 0.67 |
| invite | 6 | 1.00 |
| complain | 6 | 0.50 |
| care | 6 | 1.00 |
| praise | 6 | 0.83 |
| criticize | 6 | 0.67 |
| apologize | 6 | 1.00 |
| decline | 6 | 0.67 |
| urge | 6 | 0.67 |
| inform | 6 | 0.50 |

### 자주 헷갈린 쌍(검증)

| 정답 | 예측 | 횟수 |
|---|---|---|
| urge | ask_info | 2 |
| inform | decline | 2 |
| decline | inform | 2 |
| complain | decline | 2 |
| request | inform | 1 |
| request | decline | 1 |
| praise | check_in | 1 |
| inform | check_in | 1 |
