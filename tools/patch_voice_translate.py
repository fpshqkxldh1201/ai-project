#!/usr/bin/env python3
"""Lumi 프론트 패치: (1) Fish Audio 실패 시 브라우저 목소리로 대체하지 않기 (2) 번역 요청 재시도
 - lumi-frontend/src/context/LumiContext.jsx   (5173)
 - src/main/resources/static/index.html        (8081)  + KaTeX 태그 삽입
프로젝트 루트에서:  python tools/patch_voice_translate.py --dry-run   →   python tools/patch_voice_translate.py
여러 번 실행해도 안전하다(이미 적용된 곳은 건너뜀)."""
import re, sys, pathlib

DRY = '--dry-run' in sys.argv
ROOT = pathlib.Path(__file__).resolve().parent.parent
REACT = ROOT / 'lumi-frontend/src/context/LumiContext.jsx'
STATIC = ROOT / 'src/main/resources/static/index.html'
MARK = 'ALLOW_BROWSER_VOICE'
results = []

def load(p):
    return p.read_text(encoding='utf-8') if p.exists() else None

def save(p, text):
    if not DRY:
        bak = p.with_suffix(p.suffix + '.bak')
        if not bak.exists():
            bak.write_text(p.read_text(encoding='utf-8'), encoding='utf-8')
        p.write_text(text, encoding='utf-8')

def sub1(text, pattern, repl, label, flags=re.S):
    new, n = re.subn(pattern, lambda m: repl, text, count=1, flags=flags)
    results.append(('OK  ' if n else 'MISS') + ' ' + label)
    return new

# ───────── React ─────────
t = load(REACT)
if t is None:
    results.append('SKIP ' + str(REACT) + ' (없음)')
elif MARK in t:
    results.append('SKIP LumiContext.jsx (이미 적용됨)')
else:
    t = sub1(t, r"  async function speak\(text\) \{", """  // Lumi 목소리는 Fish Audio 하나만 쓴다. true로 바꾸면 Fish가 실패했을 때 브라우저 음성으로 대신 읽는다.
  const ALLOW_BROWSER_VOICE = false
  function notifyTtsFailure() {
    const now = Date.now()
    if (now - (window.__lumiTtsToastAt || 0) < 15000) return   // 연달아 실패해도 알림은 15초에 한 번만
    window.__lumiTtsToastAt = now
    showToast('error', 'TTS', 'Fish Audio 음성을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')
  }

  async function speak(text) {""", 'React speak 헬퍼')
    t = sub1(t, r"    \} catch \(err\) \{\n      console\.error\('Fish Audio TTS 실패, 브라우저 음성으로 대체합니다:', err\)\n    \}\n    await speakBrowser\(text\)\n  \}",
"""    } catch (err) {
      console.error('Fish Audio TTS 실패:', err)
    }
    if (ALLOW_BROWSER_VOICE) { await speakBrowser(text); return }
    setActiveVisual(false, 'LISTENING')
    notifyTtsFailure()
  }""", 'React speak 폴백 제거')
    t = sub1(t, r"    patch\(\{ translating: true \}\)\n    try \{\n      const res = await fetch\('/api/chat/translate'.*?patch\(\{ translating: false, translation: null \}\)\n    \}\n  \}",
"""    patch({ translating: true })
    // 번역 모델이 잠깐 바쁠 때(502/503)를 위해 3번까지 다시 시도한다. 400대(없음·한도)는 다시 해도 같으므로 바로 멈춘다.
    let translation = null
    for (let attempt = 1; attempt <= 3 && !translation; attempt++) {
      try {
        const res = await fetch('/api/chat/translate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
          body: JSON.stringify({ text }),
        })
        if (res.ok) {
          const data = await res.json()
          if (data && typeof data.translation === 'string' && data.translation) translation = data.translation
          break
        }
        if (res.status >= 400 && res.status < 500) break
      } catch { /* 네트워크 오류 → 재시도 */ }
      if (attempt < 3) await new Promise((r) => setTimeout(r, 1200 * attempt))
    }
    patch({ translating: false, translation: translation || '번역을 불러오지 못했어요.' })
  }""", 'React 번역 재시도')
    if not any(r.startswith('MISS') for r in results):
        save(REACT, t)

# ───────── static index.html ─────────
t = load(STATIC)
if t is None:
    results.append('SKIP ' + str(STATIC) + ' (없음)')
else:
    done_voice = MARK in t
    if not done_voice:
        t = sub1(t, r"  async function speak\(text\) \{", """  // Lumi 목소리는 Fish Audio 하나만 쓴다. true로 바꾸면 Fish가 실패했을 때 브라우저 음성으로 대신 읽는다.
  const ALLOW_BROWSER_VOICE = false;
  let ttsToastAt = 0;
  function notifyTtsFailure() {
    const now = Date.now();
    if (now - ttsToastAt < 15000) return; // 연달아 실패해도 알림은 15초에 한 번만
    ttsToastAt = now;
    showToast('error', 'TTS', 'Fish Audio 음성을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
  }

  async function speak(text) {""", 'static speak 헬퍼')
        t = sub1(t, r"    \} catch \(err\) \{\n      console\.error\('Fish Audio TTS 실패, 브라우저 음성으로 대체합니다:', err\);\n    \}\n    await speakBrowser\(text\);\n  \}",
"""    } catch (err) {
      console.error('Fish Audio TTS 실패:', err);
    }
    if (ALLOW_BROWSER_VOICE) { await speakBrowser(text); return; }
    resetSpeakingVisual();
    notifyTtsFailure();
  }""", 'static speak 폴백 제거')
        t = sub1(t, r"    try \{\n      const res = await fetch\('/api/chat/translate'.*?    \} catch \{\n      ko\.remove\(\);\n    \}\n  \}",
"""    // 번역 모델이 잠깐 바쁠 때(502/503)를 위해 3번까지 다시 시도한다. 400대(없음·한도)는 다시 해도 같으므로 바로 멈춘다.
    let translation = null;
    for (let attempt = 1; attempt <= 3 && !translation; attempt++) {
      try {
        const res = await fetch('/api/chat/translate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
          body: JSON.stringify({ text }),
        });
        if (res.ok) {
          const data = await res.json();
          if (data && typeof data.translation === 'string' && data.translation) translation = data.translation;
          break;
        }
        if (res.status >= 400 && res.status < 500) break;
      } catch (e) { /* 네트워크 오류 → 재시도 */ }
      if (attempt < 3) await new Promise((r) => setTimeout(r, 1200 * attempt));
    }
    body.className = 'log-text';
    body.textContent = translation || '번역을 불러오지 못했어요.'; // textContent라 번역문이 HTML로 해석되지 않는다
    consoleLog.scrollTop = consoleLog.scrollHeight;
  }""", 'static 번역 재시도')
    if '/js/vendor/katex/katex.min.js' not in t:
        t = sub1(t, r'<script src="/js/vendor/three\.r128\.min\.js"></script>',
                 '<script src="/js/vendor/three.r128.min.js"></script>\n<link rel="stylesheet" href="/js/vendor/katex/katex.min.css">\n<script src="/js/vendor/katex/katex.min.js"></script>', 'static KaTeX 태그')
    else:
        results.append('SKIP KaTeX 태그 (이미 있음)')
    if not any(r.startswith('MISS') for r in results):
        save(STATIC, t)

print('\n'.join(map(str, results)))
if any(str(r).startswith('MISS') for r in results):
    print('\n일부 위치를 찾지 못했습니다. 파일이 바뀌어 있을 수 있어요 — 아무것도 저장하지 않았습니다.'); sys.exit(1)
print('\n(dry-run: 저장 안 함)' if DRY else '\n완료. 원본은 *.bak 으로 남겼습니다.')
