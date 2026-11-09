/*
 * 상대 말 의도 분석 — 여자친구·부모님·친구·상사·동료 같은 상대방이 메시지나 직접 한 말을 알려 주면, 그 말이 무엇을 바라는 말인지
 * 직접 학습한 로컬 분류기(12개 의도)로 분류해 카드로 알려 준다.
 *
 * 입력은 세 가지: ① 붙여넣기/직접 입력 + 관계 선택 ② 말로 들려주기(마이크 → 서버 STT → 글로 바뀐 것을 확인·수정한 뒤 분석)
 *                 ③ 대화 캡처에서 읽기(화면 분석과 같은 "보내기 전 확인·가리기" 창을 거친다)
 *
 * 개인정보: ①②의 글은 우리 서버의 로컬 분류기로만 가고(외부 AI로 나가지 않음) 저장하지 않는다.
 *   ②는 음성을 글자로 바꾸려고 서버 STT(OpenAI)로 보낸다. ③은 글자를 읽으려고 그림이 Gemini로 나간다 — 그래서 확인·가리기 후에만.
 * 같은 파일을 8081(정적)과 5173(React)이 함께 쓴다. 화면 캡처 도구는 screenshare.js가 노출한 window.__lumiShareKit을 쓴다.
 */
(function () {
  'use strict';
  if (window.__lumiOtherIntent) return;
  window.__lumiOtherIntent = true;

  const lang = String(document.documentElement.lang || navigator.language || 'ko').toLowerCase();
  const KO = lang.startsWith('ko') || /[가-힣]/.test(document.title || '');
  const T = KO ? {
    btn: '💬 상대 말 분석', title: '상대 말의 의도 분석',
    desc: '상대가 한 말을 알려 주면 무슨 뜻으로 한 말인지 분류해 드려요.',
    rel: '누가 한 말인가요?',
    rels: [['partner', '연인'], ['parent', '부모님'], ['friend', '친구'], ['boss', '상사'], ['colleague', '동료'], ['other', '기타']],
    ph: '상대가 한 말을 붙여 넣거나 적어 주세요.\n줄마다 한 문장 (최대 8줄)\n※ 내가 한 말은 빼고 상대 말만',
    mic: '🎤 말로 들려주기', micStop: '⏹ 그만 듣기', micBusy: '글자로 바꾸는 중…', cap: '🖥 대화 캡처에서 읽기',
    go: '분석하기', close: '닫기',
    privacy: '붙여 넣은 글은 이 서버의 분류기로만 분석하고 저장하지 않아요. 🎤은 음성을 글자로 바꾸려고 OpenAI로 보내고, 🖥은 글자를 읽으려고 Gemini로 그림을 보내요(보내기 전에 확인·가리기).',
    empty: '상대가 한 말을 입력해 주세요.', analyzing: '분석 중…', micNo: '이 브라우저에서는 마이크를 쓸 수 없어요.', micOff: '서버 음성 인식이 꺼져 있어요.',
    micDenied: '마이크 사용이 허용되지 않았어요.', micErr: '음성을 글자로 바꾸지 못했어요.', micNone: '들린 말이 없어요.', recording: '듣는 중… (최대 25초)',
    capNo: '이 브라우저는 화면 공유를 지원하지 않아요.', capturing: '화면 캡처 중…', reading: '대화에서 상대 말을 읽는 중…', capErr: '화면을 가져오지 못했어요.',
    rTitle: '보내기 전에 확인하세요', rDesc: '이 대화 화면이 글자를 읽기 위해 AI(구글 Gemini)로 전송돼요. 상대의 이름·번호·주소·비밀번호처럼 민감한 정보는 마우스로 드래그해서 검게 가리세요. 내 말풍선(보통 오른쪽)은 읽지 않아요.',
    rWarn: ['대화 속 다른 사람의 개인정보가 함께 전송될 수 있어요.', '꼭 필요한 부분만 보이도록 캡처하세요.'],
    rConfirm: '민감한 정보가 없는지(또는 가렸는지) 확인했어요', rSend: '이 화면 보내기',
    e401: '로그인이 필요해요.', e429: '분석을 너무 자주 했어요. 잠시 후 다시 시도해주세요.', eFail: '분석에 실패했어요.',
    revBtn: '🔍 검토 (AI에 전송)', revBusy: '외부 AI에게도 물어 비교하는 중…', revFail: '검토에 실패했어요. 잠시 후 다시 시도해주세요.',
  } : {
    btn: '💬 Their intent', title: 'What did they mean?',
    desc: 'Tell Lumi what someone said and it classifies what they likely want.',
    rel: 'Who said it?',
    rels: [['partner', 'Partner'], ['parent', 'Parent'], ['friend', 'Friend'], ['boss', 'Boss'], ['colleague', 'Colleague'], ['other', 'Other']],
    ph: 'Paste or type what they said.\nOne sentence per line (max 8)\nLeave out your own words',
    mic: '🎤 Say it', micStop: '⏹ Stop', micBusy: 'Transcribing…', cap: '🖥 Read from a chat capture',
    go: 'Analyze', close: 'Close',
    privacy: 'Pasted text is analyzed only by this server’s classifier and not stored. 🎤 sends audio to OpenAI to transcribe; 🖥 sends the image to Gemini to read the text (after you review and black out).',
    empty: 'Enter what they said.', analyzing: 'Analyzing…', micNo: 'Microphone is not available in this browser.', micOff: 'Server speech recognition is off.',
    micDenied: 'Microphone permission was denied.', micErr: 'Could not transcribe the audio.', micNone: 'Nothing was heard.', recording: 'Listening… (up to 25s)',
    capNo: 'This browser does not support screen sharing.', capturing: 'Capturing…', reading: 'Reading their messages…', capErr: 'Could not capture the screen.',
    rTitle: 'Check before sending', rDesc: 'This chat screenshot will be sent to an AI (Google Gemini) to read the text. Black out names, numbers, addresses or passwords by dragging. Your own bubbles (usually on the right) are not read.',
    rWarn: ['Other people’s personal information in the chat may be sent along.', 'Capture only the part you need.'],
    rConfirm: 'I checked there is no sensitive info (or I covered it)', rSend: 'Send this screen',
    e401: 'Please sign in.', e429: 'Too many requests — try again shortly.', eFail: 'Analysis failed.',
    revBtn: '🔍 Review (sends to AI)', revBusy: 'Asking an external AI to compare…', revFail: 'Review failed. Please try again shortly.',
  };

  const MONO = "12px 'Share Tech Mono', monospace";
  const REL_KEY = 'lumi.otherIntentRelation';
  const MAX_REC_MS = 25000;
  const MAX_AUDIO = 880 * 1024;
  const kit = () => window.__lumiShareKit || null;
  const css = (el, o) => { Object.assign(el.style, o); return el; };
  const csrf = () => { const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/); return m ? decodeURIComponent(m[1]) : ''; };
  const store = {
    get() { try { return localStorage.getItem(REL_KEY) || ''; } catch (e) { return ''; } },
    set(v) { try { localStorage.setItem(REL_KEY, v); } catch (e) { /* 저장 불가 환경이면 무시 */ } },
  };

  function build() {
    let relation = T.rels.some((r) => r[0] === store.get()) ? store.get() : 'partner';
    let busy = false;
    let rec = null; // { mr, stream, timer, chunks }

    const btn = css(document.createElement('button'), {
      position: 'fixed', left: '50%', top: '24px', transform: 'translateX(-50%)', zIndex: '59', padding: '6px 12px', font: MONO, letterSpacing: '.04em',
      color: '#FFA94D', background: 'rgba(6,16,26,.88)', border: '1px solid rgba(255,169,77,.5)', cursor: 'pointer', whiteSpace: 'nowrap',
    });
    btn.type = 'button'; btn.id = 'lumiOtherIntentBtn'; btn.textContent = T.btn; btn.setAttribute('aria-haspopup', 'dialog'); btn.setAttribute('aria-expanded', 'false');

    const panel = css(document.createElement('div'), {
      position: 'fixed', left: '50%', top: '62px', transform: 'translateX(-50%)', zIndex: '60', width: 'min(440px, calc(100vw - 32px))', maxHeight: 'calc(100vh - 80px)', overflow: 'auto',
      boxSizing: 'border-box', padding: '12px', font: MONO, color: '#A9C7D3', background: 'rgba(6,16,26,.97)', border: '1px solid rgba(255,169,77,.5)', display: 'none',
    });
    panel.id = 'lumiOtherIntentPanel'; panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-label', T.title);

    const mk = (txt, kind) => {
      const primary = kind === 'primary', amber = kind === 'amber';
      const b = css(document.createElement('button'), {
        padding: '6px 10px', font: MONO, cursor: 'pointer', color: primary ? '#06101A' : amber ? '#FFA94D' : '#3FE0FF',
        background: primary ? '#FFA94D' : 'transparent', border: primary ? '0' : '1px solid ' + (amber ? 'rgba(255,169,77,.5)' : 'rgba(63,224,255,.4)'),
      });
      b.type = 'button'; b.textContent = txt; return b;
    };
    const h = css(document.createElement('div'), { color: '#fff', marginBottom: '6px', fontSize: '13px' }); h.textContent = T.title;
    const d = css(document.createElement('div'), { marginBottom: '8px', lineHeight: '1.5' }); d.textContent = T.desc;

    // 관계 선택(라디오 그룹)
    const relLab = css(document.createElement('div'), { marginBottom: '4px', fontSize: '11px', opacity: '.85' }); relLab.textContent = T.rel; relLab.id = 'lumiOiRelLabel';
    const relRow = css(document.createElement('div'), { display: 'flex', flexWrap: 'wrap', gap: '6px', marginBottom: '8px' });
    relRow.setAttribute('role', 'radiogroup'); relRow.setAttribute('aria-labelledby', 'lumiOiRelLabel');
    const relBtns = T.rels.map(([key, label]) => {
      const b = mk(label, 'amber'); b.setAttribute('role', 'radio'); b.dataset.key = key; return b;
    });
    const paintRel = () => relBtns.forEach((b) => {
      const on = b.dataset.key === relation;
      b.setAttribute('aria-checked', String(on)); b.tabIndex = on ? 0 : -1;
      css(b, { background: on ? '#FFA94D' : 'transparent', color: on ? '#06101A' : '#FFA94D' });
    });
    relBtns.forEach((b, i) => {
      b.addEventListener('click', () => { relation = b.dataset.key; store.set(relation); paintRel(); });
      b.addEventListener('keydown', (e) => { // 화살표로 이동(라디오 그룹 관례)
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
        e.preventDefault();
        const n = relBtns[(i + (e.key === 'ArrowRight' ? 1 : relBtns.length - 1)) % relBtns.length];
        n.click(); n.focus();
      });
      relRow.appendChild(b);
    });
    paintRel();

    const ta = css(document.createElement('textarea'), {
      width: '100%', boxSizing: 'border-box', padding: '8px', font: MONO, color: '#fff', background: 'rgba(0,0,0,.4)', border: '1px solid rgba(63,224,255,.3)',
      resize: 'vertical', minHeight: '92px', userSelect: 'text', marginBottom: '8px',
    });
    ta.rows = 4; ta.maxLength = 1800; ta.placeholder = T.ph; ta.setAttribute('aria-label', T.ph.split('\n')[0]); ta.autocomplete = 'off';

    const micBtn = mk(T.mic, 'amber'), capBtn = mk(T.cap, 'amber');
    const srcRow = css(document.createElement('div'), { display: 'flex', flexWrap: 'wrap', gap: '6px', marginBottom: '8px' });
    srcRow.append(micBtn, capBtn);

    const status = css(document.createElement('div'), { minHeight: '16px', marginBottom: '6px', fontSize: '11px', color: '#FFD479' });
    status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
    const note = css(document.createElement('div'), { fontSize: '11px', lineHeight: '1.5', opacity: '.8', marginBottom: '8px' }); note.textContent = T.privacy;
    const go = mk(T.go, 'primary'); go.style.flex = '1';
    const cl = mk(T.close);
    const row = css(document.createElement('div'), { display: 'flex', gap: '8px' }); row.append(go, cl);
    panel.append(h, d, relLab, relRow, ta, srcRow, status, note, row);

    const say = (msg, warn) => { status.textContent = msg || ''; status.style.color = warn === false ? '#3FE0FF' : '#FFD479'; };
    const setBusy = (v) => { busy = v; [go, capBtn].forEach((b) => { b.disabled = v; b.style.opacity = v ? '.5' : '1'; }); };
    const httpMsg = async (res) => {
      let msg = res.status === 401 || res.status === 403 ? T.e401 : res.status === 429 ? T.e429 : T.eFail;
      try { const j = await res.json(); if (j && j.error && [400, 413, 422, 429, 503].includes(res.status)) msg = j.error; } catch (e) { /* 본문 없음 */ }
      return msg;
    };
    // 결과 카드에 "검토" 버튼을 달고, 누르면 같은 글로 로컬 분류기 + 외부 AI 비교(하이브리드)를 요청한다.
    // 글은 이 브라우저 메모리에만 두고(카드 id별, 최근 10개) 저장하지 않는다.
    const ctx = new Map();
    let seq = 0;
    const emit = (card, lines, rel) => {
      if (card && Array.isArray(lines) && lines.length) {
        // 같은 종류·제목의 새 카드는 기존 카드 자리를 대신하므로(card.id가 바뀔 수 있음) 카드 id가 아니라 따로 만든 표식으로 글을 찾는다
        const token = 'r' + (++seq);
        ctx.set(token, { text: lines.join('\n'), relation: rel });
        if (ctx.size > 10) ctx.delete(ctx.keys().next().value);
        card.action = { label: T.revBtn, event: 'lumi:intent-review', data: token };
      }
      window.dispatchEvent(new CustomEvent('lumi:card', { detail: card }));
    };
    const showCard = (card, lines, rel) => {
      closePanel(false);
      emit(card, lines, rel);
    };
    let reviewing = false;
    window.addEventListener('lumi:card-action', async (ev) => {
      const d = ev && ev.detail;
      if (!d || d.event !== 'lumi:intent-review' || reviewing) return;
      const c = ctx.get(d.data);
      if (!c) return;
      reviewing = true;
      const k = kit(); if (k) k.say(T.revBusy);
      try {
        const res = await window.fetch('/api/intent/other/review', {
          method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
          body: JSON.stringify({ text: c.text, relation: c.relation, lang: KO ? 'ko' : 'en' }),
        });
        if (!res.ok) { if (k) k.say(await httpMsg(res), 5000); return; }
        const data = await res.json();
        if (data && data.card) { if (k) k.say(''); window.dispatchEvent(new CustomEvent('lumi:card', { detail: data.card })); } else if (k) k.say(T.revFail, 4000);
      } catch (e) { if (k) k.say(T.revFail, 4000); } finally { reviewing = false; }
    });

    // ───────── ① 글 분석 ─────────
    async function analyze() {
      if (busy) return;
      const text = ta.value.trim();
      if (!text) { say(T.empty); ta.focus(); return; }
      setBusy(true); say(T.analyzing, false);
      try {
        const res = await window.fetch('/api/intent/other', {
          method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
          body: JSON.stringify({ text, relation, lang: KO ? 'ko' : 'en' }),
        });
        if (!res.ok) { say(await httpMsg(res)); return; }
        const data = await res.json();
        if (data && data.card) showCard(data.card, data.lines || text.split(/\r?\n/).map((x) => x.trim()).filter(Boolean), relation); else say(T.eFail);
      } catch (e) { say(T.eFail); } finally { setBusy(false); }
    }

    // ───────── ② 말로 들려주기 ─────────
    const micSupported = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && typeof MediaRecorder !== 'undefined');
    let sttOk = null;
    async function checkStt() {
      if (sttOk !== null) return sttOk;
      try { const r = await window.fetch('/api/stt/status', { credentials: 'same-origin' }); sttOk = r.ok && !!(await r.json()).available; } catch (e) { sttOk = false; }
      return sttOk;
    }
    const pickMime = () => {
      for (const m of ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']) {
        try { if (MediaRecorder.isTypeSupported(m)) return m; } catch (e) { /* 다음 후보 */ }
      }
      return '';
    };
    const stopTracks = () => { if (rec && rec.stream) rec.stream.getTracks().forEach((t) => t.stop()); };
    function stopRec() { if (rec && rec.mr && rec.mr.state !== 'inactive') rec.mr.stop(); }
    async function startRec() {
      if (!micSupported) { say(T.micNo); return; }
      if (!(await checkStt())) { say(T.micOff); return; }
      let stream;
      try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); } catch (e) { say(e && e.name === 'NotAllowedError' ? T.micDenied : T.micNo); return; }
      const mime = pickMime();
      let mr;
      try { mr = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream); } catch (e) { stream.getTracks().forEach((t) => t.stop()); say(T.micNo); return; }
      rec = { mr, stream, chunks: [], timer: setTimeout(stopRec, MAX_REC_MS) };
      mr.ondataavailable = (e) => { if (e.data && e.data.size) rec.chunks.push(e.data); };
      mr.onstop = async () => {
        clearTimeout(rec.timer); stopTracks();
        const blob = new Blob(rec.chunks, { type: mr.mimeType || mime || 'audio/webm' });
        rec = null; micBtn.textContent = T.mic;
        if (!blob.size) { say(T.micNone); return; }
        if (blob.size > MAX_AUDIO) { say(T.micErr); return; }
        micBtn.disabled = true; micBtn.textContent = T.micBusy; say('');
        try {
          const fd = new FormData();
          fd.append('file', blob, 'speech.' + (blob.type.includes('mp4') ? 'm4a' : blob.type.includes('ogg') ? 'ogg' : 'webm'));
          fd.append('lang', KO ? 'ko' : 'en');
          const res = await window.fetch('/api/stt', { method: 'POST', headers: { 'X-XSRF-TOKEN': csrf() }, body: fd, credentials: 'same-origin' });
          if (!res.ok) { say(await httpMsg(res)); return; }
          const text = String((await res.json()).text || '').trim();
          if (!text) { say(T.micNone); return; }
          ta.value = (ta.value.trim() ? ta.value.trim() + '\n' : '') + text; // 글로 바뀐 것을 사용자가 보고 고친 뒤 분석한다
          ta.focus(); say('');
        } catch (e) { say(T.micErr); } finally { micBtn.disabled = false; micBtn.textContent = T.mic; }
      };
      mr.start();
      micBtn.textContent = T.micStop; say(T.recording, false);
    }
    micBtn.addEventListener('click', () => { if (rec) stopRec(); else startRec(); });
    if (!micSupported) { micBtn.disabled = true; micBtn.style.opacity = '.4'; micBtn.title = T.micNo; }

    // ───────── ③ 대화 캡처 ─────────
    const capSupported = !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia);
    if (!capSupported) { capBtn.disabled = true; capBtn.style.opacity = '.4'; capBtn.title = T.capNo; }
    async function fromCapture() {
      const k = kit();
      if (busy || !k) { if (!k) say(T.capNo); return; }
      const rel = relation;
      closePanel(false); // 클릭 직후 화면 선택 창이 떠야 하므로 먼저 닫는다
      setBusy(true);
      try {
        k.say(T.capturing);
        let frame;
        try { frame = await k.grabFrame(); } catch (e) { k.say(e && e.name === 'NotAllowedError' ? '' : T.capErr, 3500); return; }
        k.say('');
        const checked = await k.review(frame, { title: T.rTitle, desc: T.rDesc, warn: T.rWarn, confirm: T.rConfirm, send: T.rSend });
        if (!checked) return; // 취소 — 아무것도 전송되지 않는다
        let image;
        try { image = k.encode(checked); } catch (e) { k.say(T.capErr, 3500); return; }
        k.say(T.reading);
        const res = await window.fetch('/api/intent/other/screen', {
          method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
          body: JSON.stringify({ image, relation: rel, lang: KO ? 'ko' : 'en' }),
        });
        if (!res.ok) { k.say(await httpMsg(res), 5000); return; }
        const data = await res.json();
        if (data && data.card) { k.say(''); emit(data.card, data.lines, rel); } else k.say(T.eFail, 4000);
      } catch (e) { k.say(T.eFail, 4000); } finally { setBusy(false); }
    }
    capBtn.addEventListener('click', fromCapture);

    // ───────── 열기/닫기 ─────────
    function openPanel() { panel.style.display = 'block'; btn.setAttribute('aria-expanded', 'true'); ta.focus(); }
    function closePanel(refocus) {
      if (rec) { stopRec(); }
      panel.style.display = 'none'; btn.setAttribute('aria-expanded', 'false'); say('');
      if (refocus !== false) btn.focus();
    }
    btn.addEventListener('click', () => (panel.style.display === 'none' ? openPanel() : closePanel()));
    cl.addEventListener('click', () => closePanel());
    go.addEventListener('click', analyze);
    panel.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); closePanel(); }
      else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && e.target === ta) { e.preventDefault(); analyze(); }
    });

    // 화면 분석 버튼 바로 오른쪽에 놓는다(없으면 가운데 위).
    function place() {
      const s = document.getElementById('lumiScreenBtn');
      if (s && s.offsetWidth) {
        const r = s.getBoundingClientRect();
        css(btn, { left: Math.round(r.right + 8) + 'px', top: Math.round(r.top) + 'px', transform: 'none' });
      } else {
        css(btn, { left: '50%', top: '24px', transform: 'translateX(calc(-50% + 150px))' });
      }
    }
    document.body.append(btn, panel);
    place();
    window.addEventListener('resize', place);
    setTimeout(place, 300); setTimeout(place, 1200); // 화면 분석 버튼이 늦게 생겨도 맞춰 둔다
  }

  if (document.body) build(); else document.addEventListener('DOMContentLoaded', build);
})();
