# multilingual-e5-small — Lumi 감정 분류 모델 (직접 학습)

사용자 메시지의 감정을 분석해 Lumi의 답변 톤과 감정 배지에 쓰는 모델과 서버입니다.
공개 한국어 감정 데이터(KOTE)로 다국어 문장 인코더(multilingual-e5-small) 위에 분류기를 **이 프로젝트에서 직접 학습**합니다.

## 한눈에 보기

```
문장 → [multilingual-e5-small 인코더 + 평균 풀링] → [MLP 헤드] → 44개 정서의 확률 (multi-label, sigmoid)
     → summarize(): 감정 이름(primary/secondary) · 기분(mood) · valence · arousal · confidence
     → FastAPI POST /analyze → Lumi 서버(Spring)가 답변 톤 정책(EmotionTonePolicy)에 사용
```

- **정서 44개**: KOTE의 라벨 체계(불평/불만, 슬픔, 기쁨, 안심/신뢰 …, 없음). 한 문장에 여러 정서가 동시에 있을 수 있어 multi-label로 학습합니다.
- **valence / arousal**: 정서마다 기분의 좋고 나쁨(valence), 에너지의 높고 낮음(arousal)을 러셀의 감정 원형 모델을 참고해 **직접 정한 표**(`scripts/emotion_labels.py`)로 계산합니다. 학습으로 얻은 값이 아닙니다.
- **중립 판정**: 가장 높은 정서 확률이 기준보다 낮으면 "중립"으로 봅니다. 이 기준은 검증셋에서 "감정 있는 문장 vs 감정 없는 문장"을 가장 잘 가르는 값(Youden J)으로 자동 선택해 `meta.json`에 저장합니다.

## 폴더

| 경로 | 내용 |
| --- | --- |
| `scripts/emotion_labels.py` | 44개 정서 표(valence·arousal·mood)와 결과 요약 규칙 |
| `scripts/emotion_model.py` | 인코더·헤드·저장/불러오기 (학습과 서버가 공유) |
| `scripts/prepare_data.py` | KOTE 데이터 내려받기와 확인 |
| `scripts/train.py` | 학습(head / finetune), 평가, 보고서, 명령 게이트 학습 |
| `scripts/prepare_extra.py` | 의도 분류기 데이터에서 감정 없는 명령 문장을 뽑아 `data/extra_*.csv` 생성 |
| `scripts/serve.py` | FastAPI 서버 |
| `scripts/smoke_test.py` | 모델 다운로드 없이 전체 경로를 1분 안에 확인 |
| `data/lumi_check.csv` | Lumi 대화체 점검 문장 80개(AI 작성, 한국어 58·영어 22) |
| `data/extra_*.csv` | 감정 없는 명령 문장·감정 대화 문장(의도 분류기 데이터에서 추출, AI 작성) |
| `models/emotion/` | 학습 결과(자동 생성) |
| `results/` | 평가 보고서(자동 생성) |

## 빠른 시작 (Windows PowerShell)

```powershell
cd C:\Users\Atomic\Desktop\CODE\Spring\Lumi\Lumi\multilingual-e5-small
python -m pip install -r requirements.txt

python scripts\prepare_data.py        # KOTE 데이터 내려받기 (약 8MB)
python scripts\prepare_extra.py       # (선택) 의도 분류기 데이터에서 명령 문장 추출 — data\extra_*.csv 는 이미 들어 있음
python scripts\smoke_test.py          # (선택) 동작 확인, 1~2분
python scripts\train.py --mode head   # 학습: 인코더 고정 + MLP 헤드
python scripts\serve.py               # 서버 실행 (127.0.0.1:8002)
```

처음 `train.py`를 실행할 때 `intfloat/multilingual-e5-small`이 내려받아집니다(이미 의도 분류기에서 받았다면 그 캐시를 씁니다).
학습이 끝나면 `results\emotion_head.md`에 결과가 정리됩니다.

## 학습 모드

| 모드 | 하는 일 | CPU 소요(대략, PC마다 다름) | 용도 |
| --- | --- | --- | --- |
| `--mode head` | 인코더는 고정, 문장 임베딩을 한 번 계산해 캐시한 뒤 MLP 헤드만 학습 | 임베딩 계산 10~25분 + 학습 1~3분 | **먼저 이것부터.** 재실행은 캐시 덕분에 1~3분 |
| `--mode finetune --max-train 8000` | 인코더까지 같이 학습 | 학습 문장 8,000개·3에폭 기준 수십 분~1시간 이상 | head 결과가 부족할 때. 진행 중 "남은 시간"이 표시되니 보고 `--max-train`을 조절 |

공통 옵션: `--limit N`(앞 N문장만, 동작 확인용), `--cpu`, `--out 폴더`, `--seeds 3`(head 모드 시드 수)

### 데이터 사용 규칙

| 데이터 | 역할 |
| --- | --- |
| KOTE train 40,000 | 학습 |
| KOTE val 5,000 | 에폭 선택(조기 종료), 확률 임계값·중립 기준 선택 |
| KOTE test 5,000 | `--final`로 **같은 설정당 한 번만** 평가 (`results/final_log.txt`에 기록, 다시 열려면 `--reopen`) |
| `data/lumi_check.csv` | 학습·튜닝에 쓰지 않고 결과만 보는 참고용 점검 |

```powershell
python scripts\train.py --mode head --final    # 설정을 확정한 뒤 마지막에 한 번만
```

### 감정 없는 명령 문장과 명령 게이트

KOTE는 온라인 댓글이라 "오늘 일정 알려줘", "내일 3시에 회의 잡아줘" 같은 **명령·질문**을 거의 배우지 못해, 이런 문장을 들뜬 감정("기대감")으로 읽는 문제가 있었다(Lumi 점검 문장에서 확인).
이를 두 가지로 줄인다.

1. **추가 학습 데이터**: Lumi 의도 분류기 데이터의 명령 문장(일정·알림·노트·검색 + 범위 밖 요청, 학습 분할 약 515개)을 "감정 없음"으로 3번 반복 학습한다.
   검증용 명령 문장(약 75개)은 조기 종료와 중립 기준을 정하는 데에만 쓴다. 의도 분류기의 **평가용 분할(test, 동결본)은 읽지 않는다.**
2. **명령 게이트**: 같은 인코더 임베딩 위의 작은 로지스틱 회귀. 명령(의도 데이터) vs 명령 아님(감정을 털어놓는 대화 + KOTE 감정 문장)으로 학습하고,
   `data/lumi_check.csv`와 같은 문장은 학습에서 뺀다. 기준은 "KOTE 감정 문장을 명령으로 잘못 거르는 비율이 1% 이하이고 감정 대화 검증 문장은 하나도 안 걸리는" 선에서
   명령을 가장 많이 잡는 값으로 검증셋에서 정한다(감정을 놓치는 것이 명령을 놓치는 것보다 나쁘다). 게이트가 걸리면 서버는 정서 확률과 상관없이 중립을 돌려준다.
   - 사진이 같이 오면 글은 건너뛰고 사진 표정만 본다.
   - 이 문장들은 AI가 쓴 것이다. 보고서·발표에서 "감정 없는 명령 문장은 의도 분류기용 AI 작성 데이터"라고 밝힌다.

```powershell
python scripts\train.py --mode head                  # 추가 데이터·게이트 포함 (기본)
python scripts\train.py --mode head --no-extra       # 예전 방식(비교용)
python scripts\train.py --mode head --no-gate        # 게이트만 끔
python scripts\probe.py "오늘 일정 알려줘" "오늘 너무 힘들었어"   # 문장별 확률·명령 확률 확인
```

KOTE 검증 지표는 이전과 같은 KOTE 검증 문장으로만 계산하므로 이전 결과와 비교할 수 있다.
테스트셋(`--final`)은 학습 문장 수가 달라져 새 설정으로 취급되어 이 설정으로는 한 번 열 수 있다. 설정을 확정한 뒤 마지막에 한 번만 사용한다.

## 결과를 읽는 법

`results\emotion_head.md`에는 세 부분이 있습니다.

1. **검증셋(KOTE val)**: micro/macro-F1, top-1 적중률, 긍정/부정 방향 정확도. KOTE 논문의 기준 모델은 평균 F1 0.56 수준입니다.
2. **Lumi 대화체 점검 문장**: KOTE는 온라인 댓글이라 Lumi 대화와 말투가 다릅니다. 이 표의 "방향"과 "기분 일치"가 실제 Lumi에서의 느낌에 더 가깝습니다. 특히 "일정 알려줘" 같은 **명령 문장이 중립으로 나오는지**를 확인하세요. 틀린 문장 목록이 같이 나옵니다.
3. **테스트셋**: `--final`을 실행했을 때만 나옵니다.

점검 문장 표에는 "정답 종류별"(긍정·부정 감정 / 감정 없음) 결과가 같이 나온다.
감정 없음(명령·질문) 칸은 추가 학습 데이터와 같은 방식으로 쓴 문장이라 낙관적으로 나올 수 있고, **긍정·부정 감정 문장이 학습에 쓰지 않은 말투이므로 실제 일반화 정도에 더 가깝다.**

점검 문장 결과가 나쁘면 이 점검 문장에 맞춰 규칙을 고치지 말고(그러면 점검의 의미가 없어집니다), 학습 쪽을 개선하세요: `--mode finetune`, 또는 대화체 문장을 학습 데이터에 추가하는 방법이 있습니다.

## 서버와 Lumi 연동

```powershell
python scripts\serve.py
# 다른 모델 폴더/포트: $env:EMOTION_MODEL_DIR="models\emotion"; $env:EMOTION_PORT="8002"; python scripts\serve.py
```

- 요청: `POST /analyze` `{"type": "text", "text": "오늘 너무 힘들었어"}`
- 응답: `{"primary_emotion": "...", "secondary_emotion": "...", "mood": "melancholy", "valence": -0.6, "arousal": -0.5, "confidence": 0.7, "model_version": "..."}`
- `GET /health`로 상태 확인(`gate_enabled`: 명령 게이트가 켜져 있는지). 서버는 `127.0.0.1`에서만 받고, 받은 문장·사진은 저장하거나 로그에 남기지 않습니다(길이와 처리 시간만 기록).
- Lumi(Spring)는 `emotion.server.base-url`(기본 `http://localhost:8002`)로 호출합니다. 환경변수로 바꾸려면 `EMOTION_SERVER_BASE_URL`을 사용합니다. 서버가 꺼져 있으면 감정 배지와 톤 조절만 빠지고 대화는 그대로 동작합니다.

### 사진 표정 분석 (선택, 실험적)

기본은 꺼져 있습니다(사진을 보내면 감정 배지만 생략됩니다). 켜려면 얼굴 표정 분류 모델을 지정합니다.

```powershell
$env:EMOTION_IMAGE_MODEL="trpakov/vit-face-expression"; python scripts\serve.py
```

FER2013으로 학습한 7종 표정 모델(Apache-2.0, 모델 카드의 테스트 정확도 0.7116)이고, 7종 표정을 위 44개 정서 중 대응하는 것에 연결합니다. 얼굴이 찍힌 사진에서만 의미가 있습니다. 이 부분은 서버 코드의 입출력 경로만 점검했고 실제 모델로는 아직 검증하지 못했습니다.

## 한계

- KOTE는 온라인 댓글(영화·뉴스·커뮤니티 등)이라 Lumi의 대화체와 도메인이 다릅니다. 점검 문장으로 차이를 확인하고, 필요하면 대화체 데이터를 추가해야 합니다.
- 학습 데이터가 한국어이므로 영어 문장은 다국어 인코더의 전이에 기대는 것이며 정확도를 따로 확인해야 합니다(점검 문장에 영어 22개 포함).
- 정서 라벨은 5명이 각자 붙인 것의 합이라 잡음이 있고, 정답 기준이 사람마다 다를 수 있습니다.
- 명령 게이트는 의도 분류기용으로 AI가 쓴 문장으로 학습해서, 실제 사용자가 쓰는 말투의 명령은 놓칠 수 있다. 반대로 "위로해줘"처럼 감정이 섞인 요청이 명령으로 걸러질 수 있어 기준을 보수적으로 잡았지만 완전히 막지는 못한다. 새 문장(급우 수집)으로 따로 확인해야 한다.
- 감정 분석은 참고용 추정이며 의학적 진단이 아닙니다.

## 출처

- 데이터: KOTE (Korean Online That-gul Emotions) — Jeon, D., Lee, J., Kim, C., *User Guide for KOTE: Korean Online Comments Emotions Dataset*, arXiv:2205.05300. https://github.com/searle-j/KOTE (MIT License)
- 인코더: `intfloat/multilingual-e5-small`
- 정서의 valence·arousal 표는 러셀의 감정 원형 모델(Russell's circumplex model of affect)을 참고해 직접 설계했습니다.
