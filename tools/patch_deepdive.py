#!/usr/bin/env python3
"""Lumi 꼬리 탐색 연결 패치 — 두 화면이 deepdive.js 를 불러오게 한다.
 - lumi-frontend/src/main.jsx                (5173)  : import './deepdive.js' 한 줄
 - src/main/resources/static/index.html      (8081)  : <script src="/js/deepdive.js"></script> 한 줄
프로젝트 루트에서:  python tools/patch_deepdive.py --dry-run   →   python tools/patch_deepdive.py
여러 번 실행해도 안전하다(이미 있으면 건너뜀). 위치를 못 찾으면 아무것도 저장하지 않는다."""
import re, sys, pathlib

DRY = '--dry-run' in sys.argv
ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / 'lumi-frontend/src/main.jsx'
HTML = ROOT / 'src/main/resources/static/index.html'
res, bad = [], False

def read(p):
    return p.read_text(encoding='utf-8') if p.exists() else None

def save(p, t):
    if DRY:
        return
    bak = p.with_suffix(p.suffix + '.bak')
    if not bak.exists():
        bak.write_text(p.read_text(encoding='utf-8'), encoding='utf-8')
    p.write_text(t, encoding='utf-8')

t = read(MAIN)
if t is None:
    res.append('SKIP main.jsx (없음)')
elif 'deepdive' in t:
    if 'screenshare' in t:
        res.append('SKIP main.jsx (이미 적용됨)')
    else:
        i = t.index("import './deepdive.js'")
        e = t.index('\n', i) + 1
        t = t[:e] + "import './screenshare.js'\n" + t[e:]
        save(MAIN, t); res.append('OK   main.jsx (화면 분석 추가)')
else:
    m = re.search(r"^import .*\n(?!import)", t, re.M) or re.search(r"^import .*\n", t, re.M)
    last = None
    for mm in re.finditer(r"^import [^\n]*\n", t, re.M):
        last = mm
    if last is None:
        res.append('MISS main.jsx (import 줄을 못 찾음)'); bad = True
    else:
        t = t[:last.end()] + "import './deepdive.js'\nimport './screenshare.js'\n" + t[last.end():]
        save(MAIN, t); res.append('OK   main.jsx')

t = read(HTML)
if t is None:
    res.append('SKIP index.html (없음)')
elif '/js/deepdive.js' in t:
    if '/js/screenshare.js' in t:
        res.append('SKIP index.html (이미 적용됨)')
    else:
        a = '<script src="/js/deepdive.js"></script>'
        t = t.replace(a, a + '\n<script src="/js/screenshare.js"></script>', 1)
        save(HTML, t); res.append('OK   index.html (화면 분석 추가)')
else:
    anchor = '<script src="/js/vendor/three.r128.min.js"></script>'
    if anchor not in t:
        res.append('MISS index.html (three.js 태그를 못 찾음)'); bad = True
    else:
        t = t.replace(anchor, anchor + '\n<script src="/js/deepdive.js"></script>\n<script src="/js/screenshare.js"></script>', 1)
        save(HTML, t); res.append('OK   index.html')

print('\n'.join(res))
if bad:
    print('\n일부 위치를 찾지 못했습니다. 해당 파일은 저장하지 않았어요.'); sys.exit(1)
print('\n(dry-run: 저장 안 함)' if DRY else '\n완료. 원본은 *.bak 으로 남겼습니다.')
