/*
 * 꼬리 탐색(Deep dive) — 대화 답이 나온 뒤, 같은 주제를 단계별로 더 파고들어 카드를 차례로 덧붙인다.
 *  개념 학습: 구성 → 풀이 → 원리(그래프) → 더 알아보기   /  날씨: 예보 → 평년 비교 → 대기질   /  교통: 경로 → 교통 변수
 *
 * 어떻게 끼어드나: window.fetch를 한 겹 감싸서 POST /api/chat 의 성공 응답을 곁눈질한다. 원래 코드(React·정적 화면)는 고치지 않는다.
 * 카드는 'lumi:card' 이벤트로 내보내므로 두 화면이 같은 방식으로 받는다. 같은 파일을 8081(정적)과 5173(React)이 함께 쓴다.
 * 새 질문이 오면 진행 중인 탐색은 바로 멈춘다. 서버가 거절(429)하면 조용히 끝낸다.
 */
(function () {
  'use strict';
  if (window.__lumiDeepDive) return;
  window.__lumiDeepDive = true;

  const nativeFetch = window.fetch.bind(window);
  let runId = 0;
  let controller = null;
  let chip = null;

  function csrf() {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  function showChip(text) {
    if (!chip) {
      chip = document.createElement('div');
      chip.setAttribute('role', 'status');
      chip.setAttribute('aria-live', 'polite');
      const s = chip.style;
      s.position = 'fixed'; s.left = '50%'; s.bottom = '16px'; s.transform = 'translateX(-50%)'; s.zIndex = '60';
      s.padding = '6px 14px'; s.font = "12px 'Share Tech Mono', monospace"; s.letterSpacing = '.04em';
      s.color = '#3FE0FF'; s.background = 'rgba(6,16,26,.88)'; s.border = '1px solid rgba(63,224,255,.4)';
      s.pointerEvents = 'none';
      document.body.appendChild(chip);
    }
    chip.textContent = text;
  }

  function hideChip() {
    if (chip && chip.parentNode) chip.parentNode.removeChild(chip);
    chip = null;
  }

  async function post(path, body, signal) {
    const res = await nativeFetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() },
      body: JSON.stringify(body),
      signal,
    });
    if (!res.ok) { const e = new Error('HTTP ' + res.status); e.status = res.status; throw e; }
    return res.json();
  }

  function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

  async function run(message, chat) {
    const id = ++runId;
    if (controller) controller.abort();
    controller = new AbortController();
    const signal = controller.signal;
    const ko = /[가-힣]/.test(message);
    try {
      await sleep(700); // 본 답과 기본 카드가 먼저 자리 잡게 한다
      if (id !== runId) return;
      const plan = await post('/api/deepdive/plan', {
        message,
        reply: chat.reply,
        cardTypes: (Array.isArray(chat.cards) ? chat.cards : []).map((c) => c && c.type).filter(Boolean),
      }, signal);
      if (!plan || plan.kind === 'none' || !Array.isArray(plan.steps) || !plan.steps.length) return;
      window.dispatchEvent(new CustomEvent('lumi:deepdive', { detail: { state: 'start', kind: plan.kind } }));
      let ctx = {};
      for (let i = 0; i < plan.steps.length; i++) {
        if (id !== runId) return;
        showChip((ko ? '꼬리 탐색 · ' : 'Digging deeper · ') + (ko ? plan.steps[i].label : (i + 1)) + ' ' + (i + 1) + '/' + plan.steps.length);
        let r;
        try {
          r = await post('/api/deepdive/step', { kind: plan.kind, step: i, message, reply: chat.reply, ctx }, signal);
        } catch (e) {
          if (e && (e.name === 'AbortError' || e.status === 429)) return;
          continue; // 이 단계만 건너뛰고 다음으로
        }
        if (id !== runId) return;
        if (r && r.ctx && typeof r.ctx === 'object') ctx = r.ctx;
        if (r && r.card) window.dispatchEvent(new CustomEvent('lumi:card', { detail: r.card }));
        if (r && r.done) break;
      }
    } catch (e) {
      /* 탐색은 덤이므로 실패해도 대화에는 영향이 없다 */
    } finally {
      if (id === runId) {
        hideChip();
        window.dispatchEvent(new CustomEvent('lumi:deepdive', { detail: { state: 'end' } }));
      }
    }
  }

  window.fetch = function (input, init) {
    const p = nativeFetch(input, init);
    try {
      const url = typeof input === 'string' ? input : (input && input.url) || '';
      const path = url.replace(window.location.origin, '').split('?')[0];
      const method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      if (path === '/api/chat' && method === 'POST' && init && typeof init.body === 'string') {
        const message = JSON.parse(init.body).message;
        if (typeof message === 'string' && message.trim() && message.trim() !== '(대화 시작)') {
          runId++; // 새 질문이 오면 이전 탐색은 즉시 멈춘다(응답을 기다리는 동안에도)
          if (controller) controller.abort();
          hideChip();
          p.then((res) => {
            if (!res.ok) return null;
            return res.clone().json();
          }).then((chat) => {
            if (chat && typeof chat.reply === 'string') run(message, chat);
          }).catch(() => { /* 대화 흐름과 무관 */ });
        }
      }
    } catch (e) { /* 감싸기 실패는 무시 — 원래 fetch 결과를 그대로 돌려준다 */ }
    return p;
  };
})();
