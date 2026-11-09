"""데이터 점검: 라벨 오타·중복·같은 문장 다른 라벨·클래스 균형. 문제가 있으면 종료 코드 1."""
import sys
from collections import Counter, defaultdict

from common import DATA, FROZEN_TEST, LABELS, OOS_CATEGORIES, SOURCES, VERSIONS, grams, jaccard, normalize, read_rows, similar_pairs

NEAR = 0.7  # 이 이상이면 '거의 같은 문장'으로 경고


def validate_oos(is_rows, path=DATA / "oos_train.csv"):
    """OOS(범위 밖 요청) 문장 점검: 범주 코드, near 표기, 의도 문장과의 충돌(같거나 매우 비슷하면 오류/경고)."""
    errors, warnings = [], []
    if not path.exists():
        return errors, warnings
    oos = read_rows(path)
    print(f"\nOOS 문장 {len(oos)}개:", {c: sum(r['category'] == c for r in oos) for c in OOS_CATEGORIES},
          "| far", sum(r['near'] == '0' for r in oos), "/ near", sum(r['near'] == '1' for r in oos),
          "| ko", sum(r['lang'] == 'ko' for r in oos), "/ en", sum(r['lang'] == 'en' for r in oos))
    norm_is = {normalize(r["text"]): r for r in is_rows}
    gis = [(r, grams(r["text"])) for r in is_rows]
    seen = set()
    for r in oos:
        if r["category"] not in OOS_CATEGORIES:
            errors.append(f"OOS id {r['id']}: 알 수 없는 범주 '{r['category']}'")
        if r["near"] not in ("0", "1"):
            errors.append(f"OOS id {r['id']}: near는 0/1")
        if r["lang"] not in ("ko", "en"):
            errors.append(f"OOS id {r['id']}: lang은 ko/en")
        if r.get("source") not in SOURCES:
            errors.append(f"OOS id {r['id']}: source 오류 '{r.get('source')}'")
        k = normalize(r["text"])
        if k in seen:
            errors.append(f"OOS 중복: {r['text']}")
        seen.add(k)
        if k in norm_is:
            errors.append(f"OOS 문장이 의도 문장과 똑같음: {r['text']} (의도 id {norm_is[k]['id']})")
            continue
        g = grams(r["text"])
        for ir, x in gis:
            if jaccard(g, x) >= 0.7:
                warnings.append(f"OOS가 의도 문장과 매우 비슷({jaccard(g, x):.2f}): '{r['text']}' ↔ '{ir['text']}' ({ir['label']})")
                break
    return errors, warnings


def main(path=DATA / "intents.csv"):
    rows = read_rows(path)
    errors, warnings = [], []

    for r in rows:
        if r.get("label") not in LABELS:
            errors.append(f"id {r.get('id')}: 알 수 없는 라벨 '{r.get('label')}' → {r['text']}")
        if len(r.get("text", "")) < 2:
            errors.append(f"id {r.get('id')}: 문장이 너무 짧음")
        if r.get("hard") not in ("0", "1"):
            errors.append(f"id {r.get('id')}: hard는 0 또는 1이어야 함 (현재 '{r.get('hard')}')")
        if r.get("lang") not in ("ko", "en"):
            errors.append(f"id {r.get('id')}: lang은 ko 또는 en (현재 '{r.get('lang')}')")
        if r.get("source") not in SOURCES:
            errors.append(f"id {r.get('id')}: source는 {SOURCES} 중 하나 (현재 '{r.get('source')}')")
        if r.get("version") not in VERSIONS:
            errors.append(f"id {r.get('id')}: version은 {VERSIONS} 중 하나 (현재 '{r.get('version')}')")

    seen = defaultdict(list)
    for r in rows:
        seen[normalize(r["text"])].append(r)
    for key, group in seen.items():
        if len(group) > 1:
            labels = {g["label"] for g in group}
            msg = f"같은 문장 {len(group)}개: {group[0]['text']} (id {', '.join(g['id'] for g in group)})"
            (errors if len(labels) > 1 else warnings).append(msg + (" — 라벨이 서로 다름!" if len(labels) > 1 else ""))

    for i, j, s in similar_pairs(rows, NEAR):
        a, b = rows[i], rows[j]
        if normalize(a["text"]) == normalize(b["text"]):
            continue
        tag = "라벨 다름!" if a["label"] != b["label"] else "같은 라벨"
        warnings.append(f"비슷한 문장({s:.2f}, {tag}): '{a['text']}' ↔ '{b['text']}'")

    ids = Counter(r["id"] for r in rows)
    for i, n in ids.items():
        if n > 1:
            errors.append(f"id {i} 가 {n}번 나옴")

    # 동결 테스트(v1)와 너무 비슷한 새 문장은 누수 위험 → 경고(split.py가 학습·검증에서 자동 제외)
    if FROZEN_TEST.exists():
        frozen = read_rows(FROZEN_TEST)
        fids = {r["id"] for r in frozen}
        fg = [(r, grams(r["text"])) for r in frozen]
        for r in rows:
            if r["id"] in fids:
                continue
            g = grams(r["text"])
            for fr, x in fg:
                if jaccard(g, x) >= 0.6:
                    warnings.append(f"동결 테스트와 비슷(≥0.6): '{r['text']}' ↔ '{fr['text']}' → 학습에서 제외됩니다")
                    break

    by_label = Counter(r["label"] for r in rows)
    print(f"총 {len(rows)}문장")
    print(f"{'label':<14}{'total':>6}{'ko':>5}{'en':>5}{'hard':>6}")
    for lab in LABELS:
        sub = [r for r in rows if r["label"] == lab]
        print(f"{lab:<14}{len(sub):>6}{sum(r['lang']=='ko' for r in sub):>5}{sum(r['lang']=='en' for r in sub):>5}{sum(r['hard']=='1' for r in sub):>6}")
        if len(sub) < 25:
            warnings.append(f"{lab}: {len(sub)}문장 (권장 25 이상)")
    ratio = by_label.most_common(1)[0][1] / max(1, min(by_label.values()))
    if ratio > 1.5:
        warnings.append(f"클래스 불균형: 가장 많은 쪽이 가장 적은 쪽의 {ratio:.1f}배")

    print("출처:", dict(Counter(r["source"] for r in rows)), " 버전:", dict(Counter(r["version"] for r in rows)))
    e2, w2 = validate_oos(rows)
    errors += e2
    warnings += w2

    print(f"\n오류 {len(errors)}건, 경고 {len(warnings)}건")
    for e in errors:
        print("  [오류]", e)
    for w in warnings:
        print("  [경고]", w)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
