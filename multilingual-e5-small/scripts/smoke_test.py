"""동작 확인용 스모크 테스트 (약 1~2분, 모델 다운로드 없음).

  python scripts/prepare_data.py     # 데이터가 아직 없다면
  python scripts/smoke_test.py

무작위로 초기화한 아주 작은 인코더로 '데이터 읽기 → 학습(head·finetune) → 저장 → 서버 응답' 전체 경로가
끝까지 돌아가는지만 확인한다. 이 모델은 아무것도 배우지 못하므로 정확도와는 무관하다.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

from emotion_labels import LABELS, N_LABELS, NONE_IDX, neutral_summary, summarize  # noqa: E402
from prepare_data import KOTE_DIR, read_tsv  # noqa: E402

failures = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("  [통과] " if ok else "  [실패] ") + name + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(name)


def build_tiny_encoder(dst: Path, texts) -> None:
    from tokenizers import BertWordPieceTokenizer
    from transformers import BertConfig, BertModel, BertTokenizerFast

    dst.mkdir(parents=True, exist_ok=True)
    tk = BertWordPieceTokenizer(lowercase=False)
    tk.train_from_iterator(texts, vocab_size=3000, min_frequency=2)
    tk.save_model(str(dst))
    tok = BertTokenizerFast(vocab_file=str(dst / "vocab.txt"), do_lower_case=False)
    tok.save_pretrained(dst)
    cfg = BertConfig(vocab_size=tok.vocab_size, hidden_size=64, num_hidden_layers=2, num_attention_heads=2,
                     intermediate_size=128, max_position_embeddings=128)
    BertModel(cfg).save_pretrained(dst)


def run_train(env, *args) -> int:
    cmd = [sys.executable, str(HERE / "train.py"), *args]
    return subprocess.run(cmd, env=env, cwd=ROOT).returncode


def main() -> int:
    print("1) summarize() 규칙")
    flat = [0.02] * N_LABELS
    check("감정이 약하면 중립", summarize(flat)["mood"] == "neutral")
    sad = list(flat)
    sad[LABELS.index("슬픔")] = 0.8
    sad[LABELS.index("안타까움/실망")] = 0.5
    s = summarize(sad)
    check("슬픔 문장 → sad, valence < 0", s["mood"] == "sad" and s["valence"] < -0.4 and s["primary_emotion"] == "슬픔", str(s))
    check("두 번째 감정이 채워짐", s["secondary_emotion"] == "안타까움/실망", str(s))
    joy = list(flat)
    joy[LABELS.index("행복")] = 0.9
    check("행복 문장 → happy, valence > 0", summarize(joy)["mood"] == "happy" and summarize(joy)["valence"] > 0.4)
    weak = list(flat)
    weak[LABELS.index("슬픔")] = 0.45
    check("중립 기준을 높이면 같은 문장이 중립이 됨", summarize(weak, 0.30)["mood"] == "sad" and summarize(weak, 0.50)["mood"] == "neutral")

    check("또렷한 감정은 ambiguous 아님", s["ambiguous"] is False and summarize(joy)["ambiguous"] is False)
    unsure = list(flat)
    unsure[LABELS.index("슬픔")] = 0.35
    check("애매한 감정(1등 0.35)은 ambiguous", summarize(unsure, 0.30)["ambiguous"] is True)
    faint = list(flat)
    faint[LABELS.index("슬픔")] = 0.2
    check("중립이지만 신호가 남아 있으면 ambiguous", summarize(faint, 0.30)["mood"] == "neutral" and summarize(faint, 0.30)["ambiguous"] is True)
    check("신호가 거의 없는 중립은 ambiguous 아님", summarize(flat)["ambiguous"] is False)
    sure_none = list(flat)
    sure_none[NONE_IDX] = 0.8
    sure_none[LABELS.index("슬픔")] = 0.2
    check("'없음'이 확실한 문장은 ambiguous 아님", summarize(sure_none)["ambiguous"] is False)
    check("게이트(명령) 중립은 ambiguous 아님", neutral_summary(0.99)["ambiguous"] is False)

    if not (KOTE_DIR / "train.tsv").exists():
        print("KOTE 데이터가 없습니다. 먼저 python scripts/prepare_data.py 를 실행하세요.")
        return 1
    tmp = Path(tempfile.mkdtemp(prefix="emotion_smoke_"))
    try:
        print("2) 작은 인코더 만들기")
        texts = [r[1] for r in read_tsv(KOTE_DIR / "train.tsv")[:3000]]
        enc = tmp / "tiny_encoder"
        build_tiny_encoder(enc, texts)
        check("작은 인코더 생성", (enc / "config.json").exists())
        # 추가 데이터(감정 없는 명령 문장) 흉내: 명령 문장과 감정 대화 문장을 템플릿으로 만든다
        xdir = tmp / "extra"
        xdir.mkdir()
        cmds = [f"내일 {h}시에 회의 일정 추가해줘" for h in range(1, 41)] + [f"Search the web for topic number {n}" for n in range(1, 41)]
        chats = [f"오늘 너무 힘들고 지쳐서 {w}" for w in ("울고 싶어", "아무것도 하기 싫어", "외롭다", "속상해", "화가 나", "불안해", "걱정돼", "기운이 없어")]
        for name, rows in (("command_train", cmds[:30] + cmds[40:70]), ("command_val", cmds[30:40] + cmds[70:80]),
                           ("chat_train", chats[:6]), ("chat_val", chats[6:])):
            (xdir / f"extra_{name}.csv").write_text("text,lang,source\n" + "\n".join(f"{x},ko,t" for x in rows) + "\n", encoding="utf-8")
        env = {**os.environ, "EMOTION_RESULTS_DIR": str(tmp / "results"), "EMOTION_CACHE_DIR": str(tmp / "cache"),
               "EMOTION_EXTRA_DIR": str(xdir)}

        print("3) head 모드 학습")
        rc = run_train(env, "--mode", "head", "--encoder", str(enc), "--prefix", "", "--limit", "1500", "--seeds", "2",
                       "--epochs", "4", "--hidden", "32", "--out", str(tmp / "m_head"), "--cpu")
        check("head 학습이 끝까지 실행", rc == 0)
        check("head 모델 파일", (tmp / "m_head" / "head.pt").exists() and (tmp / "m_head" / "meta.json").exists())
        check("보고서 생성", (tmp / "results" / "emotion_head.md").exists())
        check("명령 게이트 파일 생성", (tmp / "m_head" / "gate.json").exists())
        import json as _json
        mh = _json.loads((tmp / "m_head" / "meta.json").read_text(encoding="utf-8"))
        check("meta에 추가 데이터·게이트 정보", mh.get("extra", {}).get("command_train") == 60 and mh.get("gate", {}).get("n_command_val") == 20, str(mh.get("extra")))
        check("보고서에 명령 처리 절", "명령 게이트" in (tmp / "results" / "emotion_head.md").read_text(encoding="utf-8"))
        check("점검 문장별 정답 종류 표는 점검 파일이 있을 때만", True)
        rc = run_train(env, "--mode", "head", "--encoder", str(enc), "--prefix", "", "--limit", "1500", "--seeds", "1",
                       "--epochs", "2", "--hidden", "32", "--out", str(tmp / "m_noextra"), "--cpu", "--no-extra")
        check("--no-extra 학습이 끝까지 실행", rc == 0)
        check("--no-extra 이면 게이트 없음", not (tmp / "m_noextra" / "gate.json").exists())

        print("4) finetune 모드 학습 (소량)")
        rc = run_train(env, "--mode", "finetune", "--encoder", str(enc), "--prefix", "", "--limit", "300", "--epochs", "1",
                       "--bs", "16", "--hidden", "32", "--out", str(tmp / "m_ft"), "--cpu")
        check("finetune 학습이 끝까지 실행", rc == 0)
        check("미세조정 인코더 저장", (tmp / "m_ft" / "encoder" / "config.json").exists())
        check("미세조정 모드에서도 게이트 생성", (tmp / "m_ft" / "gate.json").exists())

        print("5) 테스트셋 한 번만 평가하는 보호 장치")
        a = ["--mode", "head", "--encoder", str(enc), "--prefix", "", "--limit", "300", "--seeds", "1", "--epochs", "2",
             "--hidden", "32", "--out", str(tmp / "m_final"), "--cpu", "--final"]
        check("--final 첫 실행 성공", run_train(env, *a) == 0)
        check("같은 설정으로 --final 을 다시 하면 거부", run_train(env, *a) != 0)
        check("--reopen 을 주면 다시 실행되고 기록에 남음", run_train(env, *a, "--reopen") == 0
              and "REOPEN" in (tmp / "results" / "final_log.txt").read_text(encoding="utf-8"))

        print("6) 서버 응답")
        for m in ("m_head", "m_ft"):
            os.environ["EMOTION_MODEL_DIR"] = str(tmp / m)
            for k in [k for k in sys.modules if k == "serve"]:
                del sys.modules[k]
            import serve  # noqa: E402
            from fastapi.testclient import TestClient

            with TestClient(serve.app) as c:
                h = c.get("/health").json()
                check(f"{m}: /health", h["status"] == "ok" and h["image_enabled"] is False and h["gate_enabled"] is True, str(h))
                r = c.post("/analyze", json={"type": "text", "text": "오늘 너무 힘들었어"})
                j = r.json()
                need = {"primary_emotion", "secondary_emotion", "mood", "valence", "arousal", "confidence", "model_version"}
                check(f"{m}: /analyze 응답 형식", r.status_code == 200 and need <= set(j), str(j))
                check(f"{m}: 값 범위", -1 <= j["valence"] <= 1 and -1 <= j["arousal"] <= 1 and 0 <= j["confidence"] <= 1)
                check(f"{m}: 빈 문장 거부(422)", c.post("/analyze", json={"type": "text", "text": "  "}).status_code == 422)
                check(f"{m}: 너무 긴 문장 거부(422)", c.post("/analyze", json={"type": "text", "text": "가" * 2001}).status_code == 422)
                check(f"{m}: 사진 분석 꺼짐 → 501", c.post("/analyze", json={"type": "image", "image_base64": "AAAA"}).status_code == 501)
                check(f"{m}: both는 글만으로 처리", c.post("/analyze", json={"type": "both", "text": "기분 좋아", "image_base64": "AAAA"}).status_code == 200)
                # 게이트가 '명령'이라고 판단하면 정서 확률과 상관없이 중립으로 돌려준다 (기준을 0으로 내려 모든 문장이 걸리게 함)
                keep_thr = serve.state["gate"].threshold
                serve.state["gate"].threshold = 0.0
                rg = c.post("/analyze", json={"type": "text", "text": "오늘 일정 알려줘"}).json()
                check(f"{m}: 게이트가 걸리면 중립(valence 0, mood neutral)", rg["mood"] == "neutral" and rg["valence"] == 0.0
                      and rg["primary_emotion"] == "중립" and 0 <= rg["confidence"] <= 1, str(rg))
                serve.state["gate"].threshold = 1.1
                rn = c.post("/analyze", json={"type": "text", "text": "오늘 일정 알려줘"}).json()
                check(f"{m}: 게이트 기준이 1을 넘으면 절대 걸리지 않음(모델 결과 그대로)", rn["primary_emotion"] != "중립" or rn["mood"] == "neutral", str(rn))
                serve.state["gate"].threshold = keep_thr
                if m == "m_head":
                    import base64
                    import io

                    from PIL import Image

                    buf = io.BytesIO()
                    Image.new("RGB", (16, 16), (120, 120, 120)).save(buf, "JPEG")
                    b64 = base64.b64encode(buf.getvalue()).decode()
                    # 실제 표정 모델 없이, 표정 모델이 내놓는 형식(라벨+점수 목록)을 흉내 내서 사진 경로를 확인한다
                    serve.state["pipe"] = lambda img, top_k=None: [{"label": "sad", "score": 0.85}, {"label": "neutral", "score": 0.1},
                                                                  {"label": "happy", "score": 0.05}]
                    ri = c.post("/analyze", json={"type": "image", "image_base64": b64})
                    check("사진 경로: 슬픈 표정 → 슬픔/sad", ri.status_code == 200 and ri.json()["primary_emotion"] == "슬픔"
                          and ri.json()["mood"] == "sad", ri.text)
                    rb = c.post("/analyze", json={"type": "both", "text": "기분 좋아", "image_base64": "data:image/jpeg;base64," + b64})
                    check("사진+글 경로가 동작", rb.status_code == 200, rb.text)
                    serve.state["gate"].threshold = 0.0  # 글이 명령이어도 사진 표정은 읽는다
                    rbg = c.post("/analyze", json={"type": "both", "text": "일정 알려줘", "image_base64": b64})
                    check("명령 글 + 슬픈 표정 → 사진 표정만 반영(슬픔)", rbg.status_code == 200 and rbg.json()["primary_emotion"] == "슬픔", rbg.text)
                    serve.state["gate"].threshold = keep_thr
                    check("깨진 이미지 거부(422)", c.post("/analyze", json={"type": "image", "image_base64": "@@@"}).status_code == 422)
                    serve.state["pipe"] = None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print()
    if failures:
        print(f"실패 {len(failures)}건: " + ", ".join(failures))
        return 1
    print("모두 통과했습니다. 이제 python scripts/train.py --mode head 로 실제 학습을 시작하세요.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
