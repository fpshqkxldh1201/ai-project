/*
 * 화면 공유 분석 — 사용자가 고른 화면(탭·창) 한 장을 AI에게 읽혀 결과를 카드로 보여 준다.
 *  예) earth.nullschool.net 지구본 탭을 공유 → "서해안에 북서풍이 강해요" 같은 설명 카드
 *
 * 왜 이 방식인가: 다른 사이트를 iframe으로 띄우면 보안 규칙(교차 출처) 때문에 우리 페이지가 그 화면의 픽셀을 읽을 수 없다.
 * 반면 브라우저의 화면 공유(getDisplayMedia)는 사용자가 직접 고른 화면 한 장을 받을 수 있다.
 *
 * 개인정보: 사용자가 누를 때만 동작하고, 한 프레임만 캡처한 뒤 공유를 바로 끝낸다(영상·소리는 보내지 않는다).
 * 서버는 그림을 저장하지 않는다. 같은 파일을 8081(정적)과 5173(React)이 함께 쓴다.
 *
 * 사진으로 감정 보기: 대화 응답이 "글만으로는 감정을 읽기 어렵다(emotion.ambiguous)"고 알려 오면 사진을 보여 달라는 제안을 띄운다.
 * 사진을 고르면 화면 공유와 같은 "보내기 전 확인 창"(민감정보 경고·가리기·확인란)을 거친 뒤에만 /api/emotion/deep 으로 보낸다.
 */
(function () {
  'use strict';
  if (window.__lumiScreenShare) return;
  window.__lumiScreenShare = true;

  const lang = String(document.documentElement.lang || navigator.language || 'ko').toLowerCase();
  const KO = lang.startsWith('ko') || /[가-힣]/.test(document.title || '');
  const T = KO ? {
    btn: '🖥 화면 분석', title: '화면 공유 분석',
    desc: '공유할 탭이나 창을 고르면 그 화면 한 장을 AI가 읽고 설명해 줘요.',
    q: '궁금한 점 (비워두면 요약)', go: '화면 고르고 분석', close: '닫기',
    privacy: '한 장만 캡처하고 공유는 바로 끝내요. 보내기 전에 미리보기에서 확인하고 민감한 곳은 검게 가릴 수 있어요. 서버에는 저장하지 않아요.',
    unsupported: '이 브라우저는 화면 공유를 지원하지 않아요.',
    capturing: '화면 캡처 중…', analyzing: '화면 분석 중…',
    rTitle: '보내기 전에 확인하세요', rDesc: '이 화면이 AI(구글 Gemini)로 전송돼요. 비밀번호·개인 메시지·번호 같은 민감한 정보가 보이면 마우스로 드래그해서 검게 가리세요.',
    rUndo: '가린 곳 되돌리기', rSend: '이 화면 보내기', rCancel: '취소', rConfirm: '민감한 정보가 없는지(또는 가렸는지) 확인했어요', rBoxes: '가린 영역',
    e401: '로그인이 필요해요.', e429: '분석을 너무 자주 했어요. 잠시 후 다시 시도해주세요.', eFail: '화면 분석에 실패했어요.', eCap: '화면을 가져오지 못했어요.',
    sugMsg: '글만으로는 감정을 읽기 어려워요. 사진을 보여 주시면 더 자세히 볼 수 있어요.', sugAttach: '📷 사진 보여주기', sugNo: '괜찮아요', sugGroup: '사진으로 감정 더 보기',
    pTitle: '사진을 보내기 전에 확인하세요', pDesc: '이 사진이 AI(구글 Gemini)로 전송돼요. 아래 같은 민감한 정보가 보이면 마우스로 드래그해서 검게 가리세요.',
    pWarn: ['다른 사람의 얼굴 (내 표정은 감정을 읽는 데 쓰이니 그대로 둬도 돼요)', '이름표·명찰·서류·편지·카드·화면에 보이는 번호나 글자', '집 주소나 위치를 알 수 있는 간판·우편물·창밖 풍경'],
    pNote: '사진은 보내기 전에 줄여서 다시 저장되므로 촬영 위치 같은 숨은 정보(EXIF)는 함께 가지 않아요. 서버에도 저장하지 않아요.',
    pConfirm: '민감한 정보가 없는지(또는 가렸는지) 확인했어요', pSend: '이 사진 보내기',
    pReading: '사진 여는 중…', pAnalyzing: '사진으로 감정 분석 중…', pBad: '이미지 파일만 고를 수 있어요.', pBig: '사진이 너무 커요. 20MB 이하로 골라 주세요.', pFail: '사진 분석에 실패했어요.', pOpen: '사진을 열지 못했어요.',
  } : {
    btn: '🖥 Analyze screen', title: 'Screen share analysis',
    desc: 'Pick a tab or window and the AI will read that one screenshot and explain it.',
    q: 'Question (optional)', go: 'Pick screen & analyze', close: 'Close',
    privacy: 'Only one frame is captured and sharing stops right away. You can review it and black out sensitive parts before sending. The server does not store it.',
    unsupported: 'This browser does not support screen sharing.',
    capturing: 'Capturing…', analyzing: 'Analyzing…',
    rTitle: 'Check before sending', rDesc: 'This screenshot will be sent to an AI (Google Gemini). If passwords, private messages or ID numbers are visible, drag with the mouse to black them out.',
    rUndo: 'Undo last box', rSend: 'Send this screen', rCancel: 'Cancel', rConfirm: 'I checked there is no sensitive info (or I covered it)', rBoxes: 'Covered areas',
    e401: 'Please sign in.', e429: 'Too many requests — try again shortly.', eFail: 'Screen analysis failed.', eCap: 'Could not capture the screen.',
    sugMsg: "It's hard to read your feelings from text alone. Show me a photo and I can look closer.", sugAttach: '📷 Show a photo', sugNo: 'No thanks', sugGroup: 'Look closer with a photo',
    pTitle: 'Check the photo before sending', pDesc: 'This photo will be sent to an AI (Google Gemini). If you see anything sensitive like the items below, drag with the mouse to black it out.',
    pWarn: ["Other people's faces (your own face can stay — it helps read your expression)", 'Name tags, documents, letters, cards, or numbers/text on screens', 'Signs, mail or views through a window that reveal your address or location'],
    pNote: 'The photo is shrunk and re-saved before sending, so hidden data like shooting location (EXIF) is not sent. The server does not store it either.',
    pConfirm: 'I checked there is no sensitive info (or I covered it)', pSend: 'Send this photo',
    pReading: 'Opening photo…', pAnalyzing: 'Reading the photo…', pBad: 'Please choose an image file.', pBig: 'That photo is too large. Pick one under 20MB.', pFail: 'Photo analysis failed.', pOpen: 'Could not open the photo.',
  };

  const supported = !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia);
  let busy = false;
  let chip = null;
  let chipTimer = 0;

  function csrf() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }
  function css(el, o) { Object.assign(el.style, o); return el; }
  const MONO = "12px 'Share Tech Mono', monospace";

  function say(text, ms) {
    clearTimeout(chipTimer);
    if (!text) { if (chip && chip.parentNode) chip.parentNode.removeChild(chip); chip = null; return; }
    if (!chip) {
      chip = css(document.createElement('div'), {
        position: 'fixed', left: '50%', bottom: '16px', transform: 'translateX(-50%)', zIndex: '61', padding: '6px 14px',
        font: MONO, letterSpacing: '.04em', color: '#3FE0FF', background: 'rgba(6,16,26,.92)', border: '1px solid rgba(63,224,255,.4)', pointerEvents: 'none',
      });
      chip.setAttribute('role', 'status');
      chip.setAttribute('aria-live', 'polite');
      document.body.appendChild(chip);
    }
    chip.textContent = text;
    if (ms) chipTimer = setTimeout(() => say(''), ms);
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // 화면 한 프레임을 캔버스로(긴 변 1280px 이하). 공유는 캡처 직후 바로 끝낸다.
  async function grabFrame() {
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
    try {
      const v = document.createElement('video');
      v.muted = true; v.playsInline = true; v.srcObject = stream;
      await v.play();
      if (!v.videoWidth) await new Promise((r) => { v.onloadedmetadata = r; setTimeout(r, 1500); });
      await sleep(350); // 공유 직후 첫 프레임이 그려질 시간
      const w0 = v.videoWidth, h0 = v.videoHeight;
      if (!w0 || !h0) throw new Error('no frame');
      const k = Math.min(1, 1280 / Math.max(w0, h0));
      const c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(w0 * k)); c.height = Math.max(1, Math.round(h0 * k));
      c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
      return c;
    } finally {
      stream.getTracks().forEach((t) => t.stop()); // 캡처가 끝나면 공유를 즉시 종료
    }
  }

  // 서버 한도(약 1.6MB)에 맞추려고 너무 크면 화질·크기를 더 낮춘다.
  function encode(c) {
    for (const [maxSide, q] of [[1280, 0.72], [1024, 0.62], [800, 0.55]]) {
      const k = Math.min(1, maxSide / Math.max(c.width, c.height));
      let t = c;
      if (k < 1) {
        t = document.createElement('canvas');
        t.width = Math.max(1, Math.round(c.width * k)); t.height = Math.max(1, Math.round(c.height * k));
        t.getContext('2d').drawImage(c, 0, 0, t.width, t.height);
      }
      const url = t.toDataURL('image/jpeg', q);
      if (url.length < 2000000) return url;
    }
    throw new Error('too big');
  }

  // 보내기 전 확인 창: 찍힌 화면을 보여 주고, 드래그로 검게 가릴 수 있다. 확인란을 체크해야 보낼 수 있다.
  // 취소(또는 Esc)면 null — 아무것도 전송되지 않는다.
  function review(base, o) {
    o = o || {};
    return new Promise((resolve) => {
      const prevFocus = document.activeElement;
      const boxes = [];
      const ov = css(document.createElement('div'), {
        position: 'fixed', inset: '0', zIndex: '70', background: 'rgba(0,0,0,.78)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '16px',
      });
      const dlg = css(document.createElement('div'), {
        width: 'min(960px, 100%)', maxHeight: '100%', overflow: 'auto', boxSizing: 'border-box', padding: '14px', font: MONO, color: '#A9C7D3',
        background: 'rgba(6,16,26,.98)', border: '1px solid rgba(63,224,255,.5)',
      });
      dlg.setAttribute('role', 'dialog'); dlg.setAttribute('aria-modal', 'true'); dlg.setAttribute('aria-label', o.title || T.rTitle);
      const h = css(document.createElement('div'), { color: '#fff', fontSize: '14px', marginBottom: '6px' }); h.textContent = o.title || T.rTitle;
      const d = css(document.createElement('div'), { lineHeight: '1.6', marginBottom: '10px' }); d.textContent = o.desc || T.rDesc;
      let warn = null;
      if (o.warn && o.warn.length) {
        warn = css(document.createElement('ul'), { margin: '0 0 10px', padding: '8px 10px 8px 26px', lineHeight: '1.6', color: '#FFD479', background: 'rgba(255,212,121,.08)', border: '1px solid rgba(255,212,121,.35)' });
        warn.setAttribute('aria-label', o.title || T.rTitle);
        for (const w of o.warn) { const li = document.createElement('li'); li.textContent = w; warn.appendChild(li); }
      }

      const cv = document.createElement('canvas');
      cv.width = base.width; cv.height = base.height;
      css(cv, { display: 'block', width: '100%', maxHeight: '60vh', objectFit: 'contain', background: '#000', cursor: 'crosshair', touchAction: 'none', border: '1px solid rgba(63,224,255,.3)' });
      cv.setAttribute('aria-label', T.rBoxes);
      const g = cv.getContext('2d');
      let drag = null;
      const paint = () => {
        g.drawImage(base, 0, 0);
        g.fillStyle = '#000';
        for (const r of boxes) g.fillRect(r.x, r.y, r.w, r.h);
        if (drag) { g.fillStyle = 'rgba(0,0,0,.85)'; g.fillRect(drag.x, drag.y, drag.w, drag.h); g.strokeStyle = '#3FE0FF'; g.strokeRect(drag.x, drag.y, drag.w, drag.h); }
        count.textContent = T.rBoxes + ': ' + boxes.length;
      };
      // 화면에 맞춰 줄여 보이는 캔버스라 좌표를 실제 픽셀로 환산한다(object-fit: contain의 여백 포함).
      const pt = (e) => {
        const r = cv.getBoundingClientRect();
        const s = Math.min(r.width / cv.width, r.height / cv.height);
        const ox = (r.width - cv.width * s) / 2, oy = (r.height - cv.height * s) / 2;
        return { x: Math.max(0, Math.min(cv.width, (e.clientX - r.left - ox) / s)), y: Math.max(0, Math.min(cv.height, (e.clientY - r.top - oy) / s)) };
      };
      let start = null;
      cv.addEventListener('pointerdown', (e) => { start = pt(e); drag = { x: start.x, y: start.y, w: 0, h: 0 }; cv.setPointerCapture(e.pointerId); });
      cv.addEventListener('pointermove', (e) => {
        if (!start) return;
        const p2 = pt(e);
        drag = { x: Math.min(start.x, p2.x), y: Math.min(start.y, p2.y), w: Math.abs(p2.x - start.x), h: Math.abs(p2.y - start.y) };
        paint();
      });
      const endDrag = () => {
        if (drag && drag.w > 4 && drag.h > 4) boxes.push(drag);
        start = null; drag = null; paint();
      };
      cv.addEventListener('pointerup', endDrag);
      cv.addEventListener('pointercancel', endDrag);

      const count = css(document.createElement('div'), { fontSize: '11px', opacity: '.8', margin: '6px 0' });
      const lab = css(document.createElement('label'), { display: 'flex', gap: '8px', alignItems: 'center', margin: '8px 0', cursor: 'pointer', color: '#fff' });
      const chk = document.createElement('input'); chk.type = 'checkbox';
      const lt = document.createElement('span'); lt.textContent = o.confirm || T.rConfirm;
      lab.append(chk, lt);
      const row = css(document.createElement('div'), { display: 'flex', gap: '8px', flexWrap: 'wrap' });
      const mk = (txt, primary) => {
        const b = css(document.createElement('button'), {
          padding: '7px 12px', font: MONO, cursor: 'pointer', color: primary ? '#06101A' : '#3FE0FF', background: primary ? '#3FE0FF' : 'transparent', border: primary ? '0' : '1px solid rgba(63,224,255,.4)',
        });
        b.type = 'button'; b.textContent = txt; return b;
      };
      const send = mk(o.send || T.rSend, true), undo = mk(T.rUndo, false), cancel = mk(T.rCancel, false);
      const sync = () => { send.disabled = !chk.checked; send.style.opacity = chk.checked ? '1' : '.4'; send.style.cursor = chk.checked ? 'pointer' : 'not-allowed'; };
      chk.addEventListener('change', sync); sync();
      row.append(send, undo, cancel);
      dlg.append(h, d);
      if (warn) dlg.append(warn);
      dlg.append(cv, count);
      if (o.note) { const nt = css(document.createElement('div'), { fontSize: '11px', opacity: '.8', lineHeight: '1.5', margin: '0 0 6px' }); nt.textContent = o.note; dlg.append(nt); }
      dlg.append(lab, row);
      ov.appendChild(dlg);

      const done = (val) => {
        document.removeEventListener('keydown', onKey, true);
        if (ov.parentNode) ov.parentNode.removeChild(ov);
        if (prevFocus && prevFocus.focus) prevFocus.focus();
        resolve(val);
      };
      const onKey = (e) => {
        if (e.key === 'Escape') { e.stopPropagation(); done(null); return; }
        if (e.key === 'Tab') { // 창 안에서만 돈다
          const f = [chk, send, undo, cancel].filter((x) => !x.disabled);
          const i = f.indexOf(document.activeElement);
          e.preventDefault();
          f[(i + (e.shiftKey ? -1 : 1) + f.length) % f.length].focus();
        }
      };
      document.addEventListener('keydown', onKey, true);
      undo.addEventListener('click', () => { boxes.pop(); paint(); });
      cancel.addEventListener('click', () => done(null));
      send.addEventListener('click', () => { if (chk.checked) done(cv); });
      document.body.appendChild(ov);
      paint(); chk.focus();
    });
  }

  async function analyze(question) {
    if (busy) return;
    busy = true;
    try {
      say(T.capturing);
      let frame;
      try {
        frame = await grabFrame();
      } catch (e) {
        say(e && e.name === 'NotAllowedError' ? '' : T.eCap, 3500); // 사용자가 취소한 경우는 조용히
        return;
      }
      say('');
      const checked = await review(frame); // 사용자가 직접 보고 가린 뒤에야 다음 단계로
      if (!checked) return; // 취소 — 전송 없음
      let image;
      try { image = encode(checked); } catch (e) { say(T.eCap, 3500); return; }
      say(T.analyzing);
      const res = await window.fetch('/api/vision/screen', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
        body: JSON.stringify({ image, question, lang: KO ? 'ko' : 'en' }),
      });
      if (!res.ok) {
        let msg = res.status === 401 || res.status === 403 ? T.e401 : res.status === 429 ? T.e429 : T.eFail;
        try { const j = await res.json(); if (j && j.error && res.status === 422) msg = j.error; } catch (e) { /* 본문 없음 */ }
        say(msg, 5000);
        return;
      }
      const data = await res.json();
      if (data && data.card) { say(''); window.dispatchEvent(new CustomEvent('lumi:card', { detail: data.card })); }
      else say(T.eFail, 4000);
    } catch (e) {
      say(T.eFail, 4000);
    } finally {
      busy = false;
    }
  }

  // ───────── 사진으로 감정 더 보기 ─────────
  const MAX_PHOTO_BYTES = 20 * 1024 * 1024;
  let bar = null;
  let barTimer = 0;
  let lastAsk = null; // 제안을 띄운 대화 {text, primary, secondary}

  function dismissBar() {
    clearTimeout(barTimer);
    if (bar && bar.parentNode) bar.parentNode.removeChild(bar);
    bar = null;
  }

  // 고른 사진 파일을 캔버스로(긴 변 1280px 이하). 다시 그리므로 촬영 위치 같은 숨은 정보(EXIF)는 사라진다.
  async function loadPhoto(file) {
    let src, w, h, url = null;
    if (window.createImageBitmap) {
      try { src = await createImageBitmap(file, { imageOrientation: 'from-image' }); } catch (e) { src = null; }
      if (src) { w = src.width; h = src.height; }
    }
    if (!src) {
      url = URL.createObjectURL(file);
      src = await new Promise((res, rej) => { const im = new Image(); im.onload = () => res(im); im.onerror = rej; im.src = url; });
      w = src.naturalWidth; h = src.naturalHeight;
    }
    try {
      if (!w || !h) throw new Error('empty');
      const k = Math.min(1, 1280 / Math.max(w, h));
      const c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(h * k));
      const g = c.getContext('2d');
      g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height); // 투명 PNG가 검게 나오지 않게
      g.drawImage(src, 0, 0, c.width, c.height);
      return c;
    } finally {
      if (url) URL.revokeObjectURL(url);
      if (src && src.close) src.close();
    }
  }

  async function deepFromPhoto(file, ctx) {
    if (busy) return;
    busy = true;
    try {
      if (!file || !/^image\//.test(file.type || '')) { say(T.pBad, 4000); return; }
      if (file.size > MAX_PHOTO_BYTES) { say(T.pBig, 4000); return; }
      say(T.pReading);
      let canvas;
      try { canvas = await loadPhoto(file); } catch (e) { say(T.pOpen, 4000); return; }
      say('');
      // 화면 공유와 같은 확인 창: 경고·가리기·확인란을 거쳐야만 보낼 수 있다. 취소(Esc)면 아무것도 전송되지 않는다.
      const checked = await review(canvas, { title: T.pTitle, desc: T.pDesc, warn: T.pWarn, note: T.pNote, confirm: T.pConfirm, send: T.pSend });
      if (!checked) return;
      let image;
      try { image = encode(checked); } catch (e) { say(T.pOpen, 4000); return; }
      say(T.pAnalyzing);
      const res = await window.fetch('/api/emotion/deep', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
        body: JSON.stringify({ image, text: ctx.text, primary: ctx.primary, secondary: ctx.secondary, lang: KO ? 'ko' : 'en' }),
      });
      if (!res.ok) {
        let msg = res.status === 401 || res.status === 403 ? T.e401 : res.status === 429 ? T.e429 : T.pFail;
        try { const j = await res.json(); if (j && j.error && res.status === 422) msg = j.error; } catch (e) { /* 본문 없음 */ }
        say(msg, 5000);
        return;
      }
      const data = await res.json();
      if (data && data.card) { say(''); window.dispatchEvent(new CustomEvent('lumi:card', { detail: data.card })); }
      else say(T.pFail, 4000);
    } catch (e) {
      say(T.pFail, 4000);
    } finally {
      busy = false;
    }
  }

  // 대화 응답이 "글만으로는 판단 어려움"일 때 사진을 청한다. 누르지 않으면 아무 일도 없고, 30초 뒤 저절로 사라진다.
  function suggest(ctx) {
    dismissBar();
    lastAsk = ctx;
    bar = css(document.createElement('div'), {
      position: 'fixed', left: '50%', bottom: '60px', transform: 'translateX(-50%)', zIndex: '61', maxWidth: 'min(520px, calc(100vw - 32px))', boxSizing: 'border-box',
      padding: '8px 12px', font: MONO, color: '#A9C7D3', background: 'rgba(6,16,26,.96)', border: '1px solid rgba(63,224,255,.5)', display: 'flex', flexWrap: 'wrap', gap: '8px', alignItems: 'center',
    });
    bar.id = 'lumiPhotoSuggest';
    bar.setAttribute('role', 'group');
    bar.setAttribute('aria-label', T.sugGroup);
    const msg = css(document.createElement('span'), { flex: '1 1 220px', lineHeight: '1.5' }); msg.textContent = T.sugMsg;
    const file = document.createElement('input');
    file.type = 'file'; file.accept = 'image/*'; file.hidden = true; file.setAttribute('aria-hidden', 'true');
    const mk = (txt, primary) => {
      const b = css(document.createElement('button'), {
        padding: '6px 10px', font: MONO, cursor: 'pointer', color: primary ? '#06101A' : '#3FE0FF', background: primary ? '#3FE0FF' : 'transparent', border: primary ? '0' : '1px solid rgba(63,224,255,.4)',
      });
      b.type = 'button'; b.textContent = txt; return b;
    };
    const yes = mk(T.sugAttach, true), no = mk(T.sugNo, false);
    yes.addEventListener('click', () => file.click()); // 클릭 안에서 바로 열어야 브라우저가 파일 선택 창을 허용한다
    file.addEventListener('change', () => {
      const f = file.files && file.files[0];
      const ask = lastAsk;
      dismissBar();
      if (f && ask) deepFromPhoto(f, ask);
    });
    no.addEventListener('click', dismissBar);
    bar.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.stopPropagation(); dismissBar(); } });
    bar.append(msg, yes, no, file);
    document.body.appendChild(bar);
    barTimer = setTimeout(dismissBar, 30000);
  }

  // POST /api/chat 의 성공 응답만 곁눈질한다(원래 코드는 고치지 않는다). 새 질문이 오면 이전 제안은 거둔다.
  (function hook() {
    const nativeFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      const p = nativeFetch(input, init);
      try {
        const url = typeof input === 'string' ? input : (input && input.url) || '';
        const path = url.replace(window.location.origin, '').split('?')[0];
        const method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
        if (path === '/api/chat' && method === 'POST' && init && typeof init.body === 'string') {
          const message = JSON.parse(init.body).message;
          if (typeof message === 'string' && message.trim() && message.trim() !== '(대화 시작)') {
            dismissBar();
            p.then((res) => (res.ok ? res.clone().json() : null)).then((chat) => {
              const e = chat && chat.emotion;
              if (e && e.ambiguous === true) {
                const clip = (v) => String(v || '').slice(0, 24);
                suggest({ text: message.trim().slice(0, 300), primary: clip(e.primaryEmotion), secondary: clip(e.secondaryEmotion) });
              }
            }).catch(() => { /* 대화 흐름과 무관 */ });
          }
        }
      } catch (e) { /* 감싸기 실패는 무시 — 원래 fetch 결과를 그대로 돌려준다 */ }
      return p;
    };
  })();

  window.__lumiPhotoEmotion = { suggest, deepFromPhoto }; // 시험·다른 화면에서 쓸 수 있게 노출
  // 대화 캡처 의도 분석(otherintent.js)이 같은 캡처·보내기 전 확인 창을 쓰도록 노출한다(새 기능이 확인·가리기 절차를 건너뛰지 못하게).
  window.__lumiShareKit = { grabFrame, encode, review, say, csrf };

  function build() {
    const btn = css(document.createElement('button'), {
      position: 'fixed', left: '50%', top: '24px', transform: 'translateX(-50%)', zIndex: '59', padding: '6px 12px', font: MONO, letterSpacing: '.04em',
      color: '#3FE0FF', background: 'rgba(6,16,26,.88)', border: '1px solid rgba(63,224,255,.4)', cursor: 'pointer',
    });
    btn.type = 'button';
    btn.id = 'lumiScreenBtn';
    btn.textContent = T.btn;
    btn.setAttribute('aria-haspopup', 'dialog');

    const panel = css(document.createElement('div'), {
      position: 'fixed', left: '50%', top: '62px', transform: 'translateX(-50%)', zIndex: '60', width: 'min(320px, calc(100vw - 32px))', padding: '12px',
      font: MONO, color: '#A9C7D3', background: 'rgba(6,16,26,.96)', border: '1px solid rgba(63,224,255,.4)', display: 'none',
    });
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-label', T.title);

    const h = css(document.createElement('div'), { color: '#fff', marginBottom: '6px', fontSize: '13px' });
    h.textContent = T.title;
    const d = css(document.createElement('div'), { marginBottom: '8px', lineHeight: '1.5' });
    d.textContent = supported ? T.desc : T.unsupported;
    const input = css(document.createElement('input'), {
      width: '100%', boxSizing: 'border-box', padding: '6px 8px', font: MONO, color: '#fff', background: 'rgba(0,0,0,.4)',
      border: '1px solid rgba(63,224,255,.3)', marginBottom: '8px',
    });
    input.type = 'text'; input.maxLength = 200; input.placeholder = T.q; input.setAttribute('aria-label', T.q); input.autocomplete = 'off';
    const note = css(document.createElement('div'), { fontSize: '11px', lineHeight: '1.5', opacity: '.8', marginBottom: '8px' });
    note.textContent = T.privacy;
    const row = css(document.createElement('div'), { display: 'flex', gap: '8px' });
    const go = css(document.createElement('button'), {
      flex: '1', padding: '6px 8px', font: MONO, color: '#06101A', background: '#3FE0FF', border: '0', cursor: 'pointer',
    });
    go.type = 'button'; go.textContent = T.go; go.disabled = !supported;
    if (!supported) go.style.opacity = '.4';
    const cl = css(document.createElement('button'), {
      padding: '6px 10px', font: MONO, color: '#3FE0FF', background: 'transparent', border: '1px solid rgba(63,224,255,.4)', cursor: 'pointer',
    });
    cl.type = 'button'; cl.textContent = T.close;
    row.append(go, cl);
    panel.append(h, d);
    if (supported) panel.append(input);
    panel.append(note, row);

    const open = () => { panel.style.display = 'block'; btn.setAttribute('aria-expanded', 'true'); (supported ? input : go).focus(); };
    const close = () => { panel.style.display = 'none'; btn.setAttribute('aria-expanded', 'false'); btn.focus(); };
    btn.addEventListener('click', () => (panel.style.display === 'none' ? open() : close()));
    cl.addEventListener('click', close);
    panel.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.stopPropagation(); close(); } });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); go.click(); } });
    go.addEventListener('click', () => {
      const q = input.value.trim();
      panel.style.display = 'none'; btn.setAttribute('aria-expanded', 'false');
      analyze(q); // 클릭 직후에 호출해야 브라우저가 화면 선택 창을 허용한다
    });
    document.body.append(btn, panel);
  }

  if (document.body) build(); else document.addEventListener('DOMContentLoaded', build);
})();
