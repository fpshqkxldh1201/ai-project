"""공개 데이터 MASSIVE(한국어 ko-KR)에서 Lumi 9개 라벨에 해당하는 문장을 골라 학습 데이터에 더한다.

MASSIVE: FitzGerald et al., 2022, "MASSIVE: A 1M-Example Multilingual Natural Language Understanding Dataset with 51 Typologically-Diverse
Languages", arXiv:2204.08582. 원문(영어 SLURP)을 사람이 현지화하고 다른 작업자가 의도·문법을 검수한 데이터이며 라이선스는 CC BY 4.0(출처 표시 필요).

  python import_massive.py --tar amazon-massive-dataset-1.1.tar.gz     # 내려받은 파일 사용
  python import_massive.py --jsonl ko-KR.jsonl                          # 이미 풀어 둔 ko-KR.jsonl
  python import_massive.py --download                                   # 직접 내려받기(약 40MB, 인터넷 필요)
  python import_massive.py --dry-run ...                                # 저장하지 않고 개수만 보기

하는 일 (원본 intents.csv 는 intents.csv.bak 으로 남기고, 이미 넣은 massive 문장은 지웠다가 다시 넣으므로 여러 번 실행해도 같은 결과):
  1) train+dev 파티션 문장 중 아래 매핑에 해당하는 것만 → data/intents.csv 에 source=massive, version=v3 로 추가 (라벨당 최대 --per-label 개)
  2) test 파티션 문장 → data/massive_ko_external_test.csv (학습에 쓰지 않는 '외부 평가용', 라벨당 최대 --test-per-label 개)
  3) 이미 있는 문장·동결 테스트 v1 과 너무 비슷한(자카드 ≥ 0.6) 문장은 뺀다(누수 방지)
뒤이어 python validate.py → python split.py → python baseline.py → python train_nn.py 를 실행한다.

매핑은 docs/label_guide.md 규칙을 따른다. 애매한 것(lists_*, general_quirky, qa_maths, datetime_query, play_game, iot_*, email_*, takeaway_*, transport_* 등)은 뺐다.
MASSIVE 문장은 'Alexa에게 하는 말'이라 Lumi 의 실제 말투와 분포가 다를 수 있으니, 효과는 검증셋과 외부 평가셋 양쪽에서 확인한다.
"""
from __future__ import annotations

import argparse
import io
import json
import random
import sys
import tarfile
import urllib.request
from collections import Counter, defaultdict

from common import DATA, FROZEN_TEST, LABELS, FIELDS, grams, jaccard, read_rows, write_rows

SEED = 42
URLS = [
    "https://huggingface.co/datasets/AmazonScience/massive/resolve/main/data/amazon-massive-dataset-1.1.tar.gz",
    "https://amazon-massive-nlu-dataset.s3.amazonaws.com/amazon-massive-dataset-1.1.tar.gz",
]
LOCALE = "ko-KR"

# MASSIVE 의도 이름({scenario}_{intent}) → Lumi 라벨
MAP = {
    "calendar_set": "schedule_add",
    "calendar_query": "schedule_get",
    "alarm_set": "reminder_add",       # '5시에 깨워줘' = 정해진 시점에 알려 달라는 요청(규칙 3)
    "alarm_query": "reminder_get",
    "play_music": "search_video",      # 음악 재생·검색 → youtube_search
    "qa_factoid": "search_web",
    "qa_definition": "search_web",
    "qa_currency": "search_web",       # 환율·시세 = search_web(규칙 9)
    "qa_stock": "search_web",
    "weather_query": "search_web",
    "news_query": "search_web",
    "general_greet": "chat_emotion",
    "general_joke": "chat_emotion",
}


def open_jsonl(args):
    if args.jsonl:
        return open(args.jsonl, encoding="utf-8")
    data = None
    if args.tar:
        data = open(args.tar, "rb").read()
    elif args.download:
        for url in URLS:
            try:
                print("내려받는 중:", url, flush=True)
                with urllib.request.urlopen(url, timeout=120) as r:
                    data = r.read()
                break
            except Exception as e:  # noqa: BLE001
                print("  실패:", e)
        if data is None:
            sys.exit("내려받지 못했습니다. https://github.com/alexa/massive 의 README 링크로 amazon-massive-dataset-1.1.tar.gz 를 받아 --tar 로 지정하세요.")
    else:
        sys.exit("--tar, --jsonl, --download 중 하나를 지정하세요.")
    tf = tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")
    for m in tf.getmembers():
        if m.name.endswith(f"{LOCALE}.jsonl"):
            return io.TextIOWrapper(tf.extractfile(m), encoding="utf-8")
    sys.exit(f"압축 파일 안에 {LOCALE}.jsonl 이 없습니다.")


def load(args):
    out = []
    with open_jsonl(args) as f:
        for line in f:
            if not line.strip():
                continue
            d = json.loads(line)
            if d.get("locale", LOCALE) != LOCALE:
                continue
            out.append(d)
    return out


def pick(pool, per_label, rng):
    """라벨마다 원래 MASSIVE 의도를 골고루(라운드 로빈) 섞어 최대 per_label 개."""
    by_label = defaultdict(lambda: defaultdict(list))
    for d in pool:
        by_label[MAP[d["intent"]]][d["intent"]].append(d)
    chosen = []
    for lab in LABELS:
        groups = [g for g in by_label[lab].values()]
        for g in groups:
            rng.shuffle(g)
        got = []
        i = 0
        while len(got) < per_label and any(groups):
            g = groups[i % len(groups)]
            if g:
                got.append(g.pop())
            i += 1
        chosen += [(lab, d) for d in got]
    return chosen


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tar")
    ap.add_argument("--jsonl")
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--per-label", type=int, default=40, help="라벨당 학습에 더할 최대 문장 수(기본 40)")
    ap.add_argument("--test-per-label", type=int, default=20, help="라벨당 외부 평가용 최대 문장 수(기본 20)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    rows_all = load(a)
    print(f"MASSIVE {LOCALE}: {len(rows_all)}문장")
    mapped = [d for d in rows_all if d.get("intent") in MAP and len(d.get("utt", "").strip()) >= 2]
    print("매핑된 문장:", len(mapped), "| 의도별:", dict(Counter(d["intent"] for d in mapped)))
    unknown = sorted({d["intent"] for d in rows_all if d.get("intent") not in MAP})
    print(f"쓰지 않는 의도 {len(unknown)}개:", ", ".join(unknown))

    src = DATA / "intents.csv"
    existing = [r for r in read_rows(src) if r.get("source") != "massive"]   # 이전에 넣은 massive 문장은 뺀다(재실행 안전)
    frozen = read_rows(FROZEN_TEST) if FROZEN_TEST.exists() else []
    ref = [grams(r["text"]) for r in existing + frozen]
    ref_norm = {"".join(r["text"].lower().split()) for r in existing + frozen}

    def clean(pool):
        out, dropped = [], 0
        seen = set()
        for d in pool:
            t = " ".join(d["utt"].split())
            k = "".join(t.lower().split())
            if k in ref_norm or k in seen:
                dropped += 1
                continue
            g = grams(t)
            if any(jaccard(g, x) >= 0.6 for x in ref):
                dropped += 1
                continue
            seen.add(k)
            out.append({**d, "utt": t})
        return out, dropped

    train_pool, d1 = clean([d for d in mapped if d["partition"] in ("train", "dev")])
    test_pool, d2 = clean([d for d in mapped if d["partition"] == "test"])
    print(f"기존 문장·동결 테스트와 겹쳐 뺀 문장: 학습 후보 {d1}개, 평가 후보 {d2}개")
    rng = random.Random(SEED)
    tr = pick(train_pool, a.per_label, rng)
    te = pick([d for d in test_pool], a.test_per_label, rng)
    # 같은 문장이 학습과 평가 양쪽에 들어가지 않게(분할은 파티션이 달라 거의 없지만 확인)
    trn = {"".join(d["utt"].lower().split()) for _, d in tr}
    te = [(l, d) for l, d in te if "".join(d["utt"].lower().split()) not in trn]

    next_id = max([int(r["id"]) for r in existing] + [1000]) + 1
    new_rows = []
    for lab, d in tr:
        new_rows.append({"id": str(next_id), "text": d["utt"], "label": lab, "lang": "ko", "hard": "0", "source": "massive", "version": "v3"})
        next_id += 1
    test_rows = [{"id": str(5001 + i), "text": d["utt"], "label": lab, "lang": "ko", "hard": "0", "source": "massive", "version": "v3"} for i, (lab, d) in enumerate(te)]

    print("\n라벨      학습 추가 / 외부 평가")
    ctr, cte = Counter(r["label"] for r in new_rows), Counter(r["label"] for r in test_rows)
    for lab in LABELS:
        print(f"{lab:<14}{ctr[lab]:>5} / {cte[lab]:>3}")
    print(f"합계: 학습 +{len(new_rows)} (기존 {len(existing)}문장 → {len(existing) + len(new_rows)}), 외부 평가 {len(test_rows)}")
    if a.dry_run:
        print("dry-run: 저장하지 않았습니다.")
        return 0
    bak = src.with_suffix(".csv.bak")
    if not bak.exists():
        bak.write_bytes(src.read_bytes())
    write_rows(src, existing + new_rows, FIELDS)
    write_rows(DATA / "massive_ko_external_test.csv", test_rows, FIELDS)
    (DATA / "MASSIVE_ATTRIBUTION.txt").write_text(
        "일부 한국어 문장은 MASSIVE(CC BY 4.0)에서 가져왔습니다.\n"
        "FitzGerald et al., 2022. MASSIVE: A 1M-Example Multilingual Natural Language Understanding Dataset with 51 Typologically-Diverse Languages. arXiv:2204.08582.\n"
        "원문은 SLURP(Bastianelli et al., 2020, EMNLP)이며, 사람이 한국어로 현지화하고 다른 작업자가 의도·문법을 검수했습니다. source=massive, version=v3 로 표시했습니다.\n"
        "라벨은 이 프로젝트의 9개 라벨로 직접 매핑한 것이라(import_massive.py의 MAP) 원 데이터의 라벨과 다를 수 있습니다.\n", encoding="utf-8")
    print("저장: data/intents.csv, data/massive_ko_external_test.csv, data/MASSIVE_ATTRIBUTION.txt")
    print("다음: python validate.py → python split.py → python baseline.py → python train_nn.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
