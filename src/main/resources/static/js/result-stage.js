// 결과 카드(8081 정적 화면용). 5173 React 버전(ResultStage.jsx)과 같은 동작을 순수 JS로 옮긴 것이다.
// Lumi가 정보를 검색해 보여주거나 일정 등을 조회할 때만 생기고, 보는 카드는 뉴런 코어 위에 크게,
// 나머지는 코어 양옆에 작게 놓인다. 다른 카드로 바꾸면 보던 카드는 양옆의 빈 자리로 간다.
//
//   카드 추가:   LumiCards.push(card)   또는 window.dispatchEvent(new CustomEvent('lumi:card', { detail: card }))
//   모두 치우기: LumiCards.clear()       또는 window.dispatchEvent(new CustomEvent('lumi:cards-clear'))
//   주소에 ?demo=cards 를 붙이면 예시 카드를 띄우는 데모 버튼이 생긴다.
(function () {
  'use strict';
  const host = document.querySelector('.center-hud');
  if (!host) return;

  // 카드 겉 글자는 서버가 정해 준 card.lang(ko/en/ja/zh/es)에 맞춰 바뀐다.
  const UI = {
    ko: { kind: { schedule: '일정', search: '웹 검색', mood: '감정 추이', reminder: '리마인더', video: '유튜브', stats: '통계', stock: '주가', table: '표', explain: '개념 설명', intent: '상대 말 의도' },
      source: '출처', big: '카드 크게 보기', bigTip: '크게 보기', close: '이 카드 닫기', bigClose: '크게 보기 닫기', bigPre: '크게 보기: ', cur: '현재 카드 ', side: '옆 카드 ', toFront: '. 누르면 앞으로 옵니다', formulas: '핵심 수식', imgAlt: '관련 이미지', open: '원문 보기' },
    en: { kind: { schedule: 'Schedule', search: 'Web search', mood: 'Mood trend', reminder: 'Reminder', video: 'YouTube', stats: 'Stats', stock: 'Stock', table: 'Table', explain: 'Concept', intent: 'Their intent' },
      source: 'Source', big: 'Enlarge card', bigTip: 'Enlarge', close: 'Close this card', bigClose: 'Close enlarged view', bigPre: 'Enlarged: ', cur: 'Current card ', side: 'Side card ', toFront: '. Press to bring it forward', formulas: 'Key formulas', imgAlt: 'Related image', open: 'Read more' },
    ja: { kind: { schedule: '予定', search: 'ウェブ検索', mood: '感情の推移', reminder: 'リマインダー', video: 'YouTube', stats: '統計', stock: '株価', table: '表', explain: '概念の説明', intent: '相手の意図' },
      source: '出典', big: 'カードを拡大', bigTip: '拡大', close: 'このカードを閉じる', bigClose: '拡大表示を閉じる', bigPre: '拡大: ', cur: '現在のカード ', side: '横のカード ', toFront: '。押すと前に出ます', formulas: '主な数式', imgAlt: '関連画像', open: '続きを読む' },
    zh: { kind: { schedule: '日程', search: '网页搜索', mood: '情绪趋势', reminder: '提醒', video: 'YouTube', stats: '统计', stock: '股价', table: '表格', explain: '概念讲解', intent: '对方意图' },
      source: '来源', big: '放大卡片', bigTip: '放大', close: '关闭此卡片', bigClose: '关闭放大视图', bigPre: '放大: ', cur: '当前卡片 ', side: '侧边卡片 ', toFront: '。点击可移到前面', formulas: '核心公式', imgAlt: '相关图片', open: '阅读原文' },
    es: { kind: { schedule: 'Agenda', search: 'Búsqueda web', mood: 'Estado de ánimo', reminder: 'Recordatorio', video: 'YouTube', stats: 'Estadísticas', stock: 'Bolsa', table: 'Tabla', explain: 'Concepto', intent: 'Intención' },
      source: 'Fuente', big: 'Ampliar tarjeta', bigTip: 'Ampliar', close: 'Cerrar esta tarjeta', bigClose: 'Cerrar vista ampliada', bigPre: 'Ampliada: ', cur: 'Tarjeta actual ', side: 'Tarjeta lateral ', toFront: '. Pulsa para traerla al frente', formulas: 'Fórmulas clave', imgAlt: 'Imagen relacionada', open: 'Leer más' },
  };
  const ui = (card) => UI[card && card.lang] || UI.ko;
  const kindOf = (card) => ui(card).kind[card.type] || card.type;
  const AMBER = { mood: 1, reminder: 1, stock: 1 };
  const CARD_W = 420, CARD_H = 460, SMALL = 0.44;
  const SLOTS = {
    front: { x: 0, y: 0, z: 60, r: 0, s: 1 },
    L1: { x: -335, y: -120, z: -90, r: 26, s: SMALL },
    R1: { x: 335, y: -120, z: -90, r: -26, s: SMALL },
    L2: { x: -335, y: 120, z: -90, r: 26, s: SMALL },
    R2: { x: 335, y: 120, z: -90, r: -26, s: SMALL },
  };
  const SMALL_ORDER = ['L1', 'R1', 'L2', 'R2'];
  const PALETTE = ['#3FE0FF', '#FFA94D', '#7C9CFF', '#5EE6A8', '#FF6A6E'];
  const UP = '#FF5C6A', DOWN = '#4D9CFF', GRID = 'rgba(63,224,255,0.18)', MUTED = '#6F8896';
  const NS = 'http://www.w3.org/2000/svg';

  let state = { cards: {}, pos: {}, recent: [] };
  let stage = null, seq = 0;

  // ── 작은 DOM 도우미 (서버/검색 결과 글자는 항상 textContent로만 넣는다) ──
  function h(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function sv(tag, attrs, text) {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }
  const fmt = (n) => Number(n).toLocaleString('ko-KR');
  const toMin = (t) => { const p = String(t).split(':'); return (+p[0]) * 60 + (+p[1]); };
  const pct = (v) => v + '%';

  // ── 차트 ──
  function timeline(tl) {
    const start = tl.from * 60, span = (tl.to - tl.from) * 60;
    const pos = (t) => ((toMin(t) - start) / span) * 100;
    const box = h('div', 'rs-tl');
    box.style.height = (40 + tl.items.length * 46) + 'px';
    for (let hr = tl.from; hr < tl.to; hr++) {
      const t = h('div', 'rs-tick'); t.style.left = pct(((hr * 60 - start) / span) * 100);
      t.appendChild(h('span', null, hr + ':00')); box.appendChild(t);
    }
    if (tl.items.some((i) => i.conflict)) {
      const s = Math.max.apply(null, tl.items.map((i) => toMin(i.start)));
      const e = Math.min.apply(null, tl.items.map((i) => toMin(i.end)));
      if (e > s) { const o = h('div', 'rs-overlap'); o.style.left = pct(((s - start) / span) * 100); o.style.width = pct(((e - s) / span) * 100); box.appendChild(o); }
    }
    tl.items.forEach((it, i) => {
      const b = h('div', 'rs-blk' + (it.conflict ? ' warn' : ''));
      b.style.left = pct(pos(it.start)); b.style.width = pct(pos(it.end) - pos(it.start)); b.style.top = (30 + i * 46) + 'px';
      b.appendChild(h('b', null, it.title)); b.appendChild(h('small', null, it.start + '–' + it.end));
      box.appendChild(b);
    });
    return box;
  }

  function donut(chart) {
    const total = chart.data.reduce((s, d) => s + d[1], 0) || 1;
    const R = 62, C = 2 * Math.PI * R;
    const wrap = h('div', 'rs-donut');
    const svg = sv('svg', { viewBox: '0 0 160 160', role: 'img', 'aria-label': '원형 그래프: ' + chart.data.map((d) => d[0] + ' ' + d[1]).join(', ') });
    svg.appendChild(sv('circle', { cx: 80, cy: 80, r: R, fill: 'none', stroke: 'rgba(63,224,255,0.08)', 'stroke-width': 24 }));
    let acc = 0;
    chart.data.forEach((d, i) => {
      const len = (d[1] / total) * C, a = Math.max(0, len - 2);
      svg.appendChild(sv('circle', { cx: 80, cy: 80, r: R, fill: 'none', stroke: PALETTE[i % PALETTE.length], 'stroke-width': 24, 'stroke-dasharray': a + ' ' + (C - a), 'stroke-dashoffset': -acc, transform: 'rotate(-90 80 80)' }));
      acc += len;
    });
    svg.appendChild(sv('text', { x: 80, y: 78, 'text-anchor': 'middle', 'font-size': 22, fill: '#fff', 'font-family': 'Orbitron, sans-serif', 'font-weight': 700 }, String(total)));
    svg.appendChild(sv('text', { x: 80, y: 96, 'text-anchor': 'middle', 'font-size': 10, fill: MUTED }, '전체 ' + (chart.unit || '')));
    wrap.appendChild(svg);
    const ul = h('ul', 'rs-legend');
    chart.data.forEach((d, i) => {
      const li = h('li'); const sw = h('i'); sw.style.background = PALETTE[i % PALETTE.length];
      li.appendChild(sw); li.appendChild(h('span', null, d[0])); li.appendChild(h('b', null, Math.round((d[1] / total) * 100) + '%')); ul.appendChild(li);
    });
    wrap.appendChild(ul);
    return wrap;
  }

  function gridLines(svg, W, H, padT, padB) {
    [0, 0.5, 1].forEach((t) => {
      const y = padT + (H - padT - padB) * (1 - t);
      svg.appendChild(sv('line', { x1: 0, x2: W, y1: y, y2: y, stroke: GRID, 'stroke-dasharray': '3 4' }));
    });
  }

  function bars(chart) {
    const W = 360, H = 190, padB = 22, padT = 18;
    const svg = sv('svg', { class: 'rs-svg', viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': '막대 그래프' });
    gridLines(svg, W, H, padT, padB);
    const max = Math.max(1, Math.max.apply(null, chart.data.map((d) => d[1])));
    const bw = (W - 20) / chart.data.length;
    const best = chart.data.reduce((a, d, i) => (d[1] > chart.data[a][1] ? i : a), 0);
    chart.data.forEach((d, i) => {
      const hh = ((H - padT - padB) * d[1]) / max, x = 10 + i * bw + bw * 0.18;
      svg.appendChild(sv('rect', { x: x, y: H - padB - hh, width: bw * 0.64, height: Math.max(hh, 1), fill: i === best ? '#FFA94D' : '#3FE0FF', 'fill-opacity': i === best ? 1 : 0.65 }));
      svg.appendChild(sv('text', { x: x + bw * 0.32, y: H - padB - hh - 5, 'text-anchor': 'middle', 'font-size': 11, fill: '#E4F8FF', 'font-family': 'Share Tech Mono, monospace' }, String(d[1])));
      svg.appendChild(sv('text', { x: x + bw * 0.32, y: H - 6, 'text-anchor': 'middle', 'font-size': 11, fill: MUTED }, d[0]));
    });
    return svg;
  }

  function line(chart) {
    const W = 360, H = 190, padB = 22, padT = 14, vals = chart.data.map((d) => d[1]);
    const lo = Math.min.apply(null, vals), hi = Math.max.apply(null, vals), rng = hi - lo || 1;
    const X = (i) => 8 + (i * (W - 16)) / Math.max(1, vals.length - 1);
    const Y = (v) => padT + (H - padT - padB) * (1 - (v - lo) / rng);
    const pts = vals.map((v, i) => X(i) + ',' + Y(v)).join(' ');
    const svg = sv('svg', { class: 'rs-svg', viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': '선 그래프' });
    gridLines(svg, W, H, padT, padB);
    svg.appendChild(sv('polygon', { points: X(0) + ',' + (H - padB) + ' ' + pts + ' ' + X(vals.length - 1) + ',' + (H - padB), fill: '#3FE0FF', 'fill-opacity': 0.12 }));
    svg.appendChild(sv('polyline', { points: pts, fill: 'none', stroke: '#3FE0FF', 'stroke-width': 2 }));
    svg.appendChild(sv('circle', { cx: X(vals.length - 1), cy: Y(vals[vals.length - 1]), r: 4, fill: '#FFA94D' }));
    const every = Math.ceil(vals.length / 6);
    chart.data.forEach((d, i) => { if (i % every === 0) svg.appendChild(sv('text', { x: X(i), y: H - 6, 'text-anchor': 'middle', 'font-size': 10, fill: MUTED }, d[0])); });
    return svg;
  }

  function candles(chart) {
    const d = chart.data, W = 360, H = 250, volH = 44, top = 8, priceH = H - volH - 22;
    const hi = Math.max.apply(null, d.map((x) => x.h)), lo = Math.min.apply(null, d.map((x) => x.l)), rng = hi - lo || 1;
    const vmax = Math.max.apply(null, d.map((x) => x.v)) || 1;
    const step = (W - 16) / d.length, bw = step * 0.62;
    const Y = (v) => top + priceH * (1 - (v - lo) / rng), cx = (i) => 8 + i * step + step / 2;
    const last = d[d.length - 1], prev = d[d.length - 2] || last;
    const diff = last.c - prev.c, pc = prev.c ? (diff / prev.c) * 100 : 0, dir = diff >= 0 ? 'up' : 'down';
    const wrap = h('div', 'rs-candle');
    const q = h('div', 'rs-quote');
    const big = h('b', dir, fmt(last.c)); big.appendChild(h('small', null, chart.unit || ''));
    q.appendChild(big);
    q.appendChild(h('span', dir, (diff >= 0 ? '▲ ' : '▼ ') + fmt(Math.abs(diff)) + ' (' + (pc >= 0 ? '+' : '') + pc.toFixed(2) + '%)'));
    wrap.appendChild(q);
    const svg = sv('svg', { class: 'rs-svg', viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': '주가 캔들 차트' });
    [0, 0.5, 1].forEach((t) => {
      const y = top + priceH * t;
      svg.appendChild(sv('line', { x1: 0, x2: W, y1: y, y2: y, stroke: GRID, 'stroke-dasharray': '3 4' }));
      svg.appendChild(sv('text', { x: W - 2, y: y - 3, 'text-anchor': 'end', 'font-size': 9, fill: MUTED, 'font-family': 'Share Tech Mono, monospace' }, fmt(Math.round(hi - rng * t))));
    });
    d.forEach((x, i) => {
      const c = x.c >= x.o ? UP : DOWN;
      svg.appendChild(sv('line', { x1: cx(i), x2: cx(i), y1: Y(x.h), y2: Y(x.l), stroke: c }));
      svg.appendChild(sv('rect', { x: cx(i) - bw / 2, y: Math.min(Y(x.o), Y(x.c)), width: bw, height: Math.max(1.5, Math.abs(Y(x.o) - Y(x.c))), fill: c }));
      svg.appendChild(sv('rect', { x: cx(i) - bw / 2, y: H - 20 - (x.v / vmax) * volH, width: bw, height: (x.v / vmax) * volH, fill: c, 'fill-opacity': 0.45 }));
    });
    const ma = [];
    d.forEach((_, i) => { if (i >= 4) ma.push(cx(i) + ',' + Y(d.slice(i - 4, i + 1).reduce((s, x) => s + x.c, 0) / 5)); });
    if (ma.length) svg.appendChild(sv('polyline', { points: ma.join(' '), fill: 'none', stroke: '#3FE0FF', 'stroke-width': 1.4 }));
    svg.appendChild(sv('text', { x: 10, y: H - 4, 'font-size': 9, fill: MUTED }, '거래량'));
    svg.appendChild(sv('text', { x: W - 2, y: H - 4, 'text-anchor': 'end', 'font-size': 9, fill: '#3FE0FF' }, '5일 이동평균'));
    wrap.appendChild(svg);
    return wrap;
  }

  function table(t) {
    const tb = h('table', 'rs-table'), thead = h('thead'), hr = h('tr');
    t.cols.forEach((c) => hr.appendChild(h('th', null, c))); thead.appendChild(hr); tb.appendChild(thead);
    const body = h('tbody');
    t.rows.forEach((r, i) => { const tr = h('tr', i === t.highlight ? 'hl' : ''); r.forEach((c) => tr.appendChild(h('td', null, c))); body.appendChild(tr); });
    tb.appendChild(body);
    return tb;
  }

  // ── 함수 그래프(서버가 식을 계산한 점) · 바로가기 ──
  const PLOT_COLORS = ['#3FE0FF', '#FFA94D', '#B6F36B'];
  function plot(chart) {
    const series = (chart.data || []).filter((s) => s && Array.isArray(s.pts) && s.pts.length > 1).slice(0, 3);
    const all = [];
    series.forEach((s) => s.pts.forEach((p) => { if (Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1])) all.push(p); }));
    const wrap = h('div', 'rs-plot');
    if (all.length < 2) return wrap;
    const W = 360, H = 200, L = 34, R = 10, T = 14, B = 24;
    let xmin = Math.min.apply(null, all.map((p) => p[0])), xmax = Math.max.apply(null, all.map((p) => p[0]));
    let ymin = Math.min.apply(null, all.map((p) => p[1])), ymax = Math.max.apply(null, all.map((p) => p[1]));
    if (ymax - ymin < 1e-9) { ymax += 1; ymin -= 1; }
    const padY = (ymax - ymin) * 0.08; ymin -= padY; ymax += padY;
    const X = (x) => L + ((x - xmin) / (xmax - xmin || 1)) * (W - L - R);
    const Y = (y) => T + (1 - (y - ymin) / (ymax - ymin)) * (H - T - B);
    const fmt = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2)).replace(/\.?0+$/, '');
    const svg = sv('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': series.map((s) => s.name).join(', ') });
    svg.appendChild(sv('rect', { x: L, y: T, width: W - L - R, height: H - T - B, fill: 'none', stroke: 'rgba(63,224,255,.25)' }));
    if (ymin < 0 && ymax > 0) svg.appendChild(sv('line', { x1: L, x2: W - R, y1: Y(0), y2: Y(0), stroke: 'rgba(255,255,255,.35)', 'stroke-dasharray': '3 3' }));
    if (xmin < 0 && xmax > 0) svg.appendChild(sv('line', { y1: T, y2: H - B, x1: X(0), x2: X(0), stroke: 'rgba(255,255,255,.35)', 'stroke-dasharray': '3 3' }));
    series.forEach((s, i) => {
      const pts = s.pts.filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1])).map((p) => X(p[0]).toFixed(1) + ',' + Y(p[1]).toFixed(1)).join(' ');
      svg.appendChild(sv('polyline', { fill: 'none', stroke: PLOT_COLORS[i], 'stroke-width': 2, 'stroke-linejoin': 'round', points: pts }));
    });
    svg.appendChild(sv('text', { x: X(xmin), y: H - 8, 'text-anchor': 'start', 'font-size': 10, fill: MUTED }, fmt(xmin)));
    svg.appendChild(sv('text', { x: X(xmax), y: H - 8, 'text-anchor': 'end', 'font-size': 10, fill: MUTED }, fmt(xmax)));
    [ymin + padY, ymax - padY].forEach((v) => svg.appendChild(sv('text', { x: L - 4, y: Y(v) + 3, 'text-anchor': 'end', 'font-size': 10, fill: MUTED }, fmt(v))));
    wrap.appendChild(svg);
    const lg = h('div', 'rs-legend');
    series.forEach((s, i) => { const sp = h('span'); const dot = h('i'); dot.style.background = PLOT_COLORS[i]; sp.appendChild(dot); sp.appendChild(document.createTextNode(String(s.name).slice(0, 40))); lg.appendChild(sp); });
    wrap.appendChild(lg);
    return wrap;
  }
  const LINK_HOSTS = ['map.kakao.com', 'map.naver.com', 'www.google.com', 'ko.wikipedia.org', 'en.wikipedia.org', 'earth.nullschool.net'];
  const okLink = (u) => { try { const x = new URL(u); return x.protocol === 'https:' && LINK_HOSTS.indexOf(x.hostname) >= 0 && (x.hostname !== 'www.google.com' || x.pathname.indexOf('/maps') === 0); } catch (e) { return false; } };

  // ── 수식(KaTeX) · 이미지 ──
  // 서버가 한 번 거른 식이지만 여기서도 trust:false·throwOnError:true로 막고, 못 읽는 식은 원문을 고정폭 글자로 보여 준다.
  function formulaBox(f) {
    const box = h('div', 'rs-formula');
    let ok = false;
    if (window.katex) {
      const t = h('div', 'rs-tex');
      try {
        window.katex.render(String(f.tex), t, { displayMode: true, throwOnError: true, trust: false, strict: 'ignore', maxExpand: 200, output: 'htmlAndMathml' });
        box.appendChild(t); ok = true;
      } catch (e) { ok = false; }
    }
    if (!ok) box.appendChild(h('code', 'rs-tex-raw', String(f.tex)));
    if (f.caption) box.appendChild(h('p', null, f.caption));
    return box;
  }
  const okImg = (u) => typeof u === 'string' && /^\/api\/media\/[\w/.-]+$/.test(u);
  const okPage = (u) => { try { const x = new URL(u); return x.protocol === 'https:' && /(^|\.)wikipedia\.org$/.test(x.hostname); } catch (e) { return false; } };
  function figure(card) {
    const im = card.image, t = ui(card);
    const fig = h('figure', 'rs-fig');
    if (okImg(im.url)) {
      const img = document.createElement('img');
      img.src = im.url; img.alt = im.caption || t.imgAlt; img.loading = 'lazy'; img.referrerPolicy = 'no-referrer';
      img.addEventListener('error', () => { img.style.display = 'none'; });
      fig.appendChild(img);
    }
    const cap = h('figcaption');
    if (im.caption) cap.appendChild(h('span', null, im.caption));
    if (im.credit) cap.appendChild(h('em', null, im.credit));
    if (okPage(im.page)) {
      const a = h('a', null, t.open + ' ↗'); a.href = im.page; a.target = '_blank'; a.rel = 'noopener noreferrer';
      cap.appendChild(a);
    }
    fig.appendChild(cap);
    return fig;
  }

  function cardBody(card) {
    const f = document.createDocumentFragment();
    if (card.lead) f.appendChild(h('p', 'rs-lead', card.lead));
    if (Array.isArray(card.formulas) && card.formulas.length) {
      const fs = h('div', 'rs-formulas'); fs.setAttribute('aria-label', ui(card).formulas);
      card.formulas.forEach((x) => { if (x && typeof x.tex === 'string') fs.appendChild(formulaBox(x)); });
      if (fs.childNodes.length) f.appendChild(fs);
    }
    if (card.image && typeof card.image === 'object') f.appendChild(figure(card));
    if (card.detail) f.appendChild(h('p', 'rs-detail', card.detail));
    if (card.timeline && card.timeline.items && card.timeline.items.length) f.appendChild(timeline(card.timeline));
    const c = card.chart;
    if (c && c.data && c.data.length) {
      if (c.kind === 'pie') f.appendChild(donut(c));
      else if (c.kind === 'bar') f.appendChild(bars(c));
      else if (c.kind === 'line') f.appendChild(line(c));
      else if (c.kind === 'candle') f.appendChild(candles(c));
      else if (c.kind === 'plot') f.appendChild(plot(c));
    }
    if (card.table && card.table.cols && card.table.rows) f.appendChild(table(card.table));
    if (card.points && card.points.length) { const ul = h('ul', 'rs-points'); card.points.forEach((p) => ul.appendChild(h('li', null, p))); f.appendChild(ul); }
    if (card.sources && card.sources.length) {
      const box = h('div', 'rs-sources');
      card.sources.forEach((s, i) => { const row = h('div'); row.appendChild(h('span', null, s)); row.appendChild(h('em', null, ui(card).source + ' ' + (i + 1))); box.appendChild(row); });
      f.appendChild(box);
    }
    if (Array.isArray(card.links)) {
      const ok = card.links.filter((l) => l && okLink(l.url)).slice(0, 4);
      if (ok.length) {
        const box = h('div', 'rs-links');
        ok.forEach((l) => { const a = h('a', null, String(l.label).slice(0, 40) + ' ↗'); a.href = l.url; a.target = '_blank'; a.rel = 'noopener noreferrer'; box.appendChild(a); });
        f.appendChild(box);
      }
    }
    if (card.note) f.appendChild(h('p', 'rs-note', card.note));
    // 카드 아래 동작 버튼(예: 의도 결과 "검토"): 누르면 lumi:card-action 이벤트로 알리기만 한다(서버가 준 라벨은 20자로 제한)
    if (card.action && card.action.label && card.action.event) {
      const ab = h('button', 'rs-action', String(card.action.label).slice(0, 20));
      ab.type = 'button';
      ab.addEventListener('click', (e) => { e.stopPropagation(); window.dispatchEvent(new CustomEvent('lumi:card-action', { detail: { id: card.id, event: String(card.action.event), data: String(card.action.data || '').slice(0, 64) } })); });
      f.appendChild(ab);
    }
    return f;
  }

  // ── 상태 (React 버전의 reducer와 같은 규칙) ──
  // 같은 카드인지: 종류와 제목이 같거나, 검색 카드끼리 출처가 절반 넘게 겹치면 같은 결과로 본다.
  function sameCard(a, b) {
    if (a.type !== b.type) return false;
    if (a.title === b.title) return true;
    if (a.type !== 'search' || !a.sources || !b.sources || !a.sources.length || !b.sources.length) return false;
    const common = a.sources.filter((x) => b.sources.indexOf(x) >= 0).length;
    return common / Math.min(a.sources.length, b.sources.length) > 0.5;
  }
  const touch = (recent, id) => [id].concat(recent.filter((x) => x !== id));
  const frontOf = (pos) => Object.keys(pos).find((k) => pos[k] === 'front');

  function doPush(card) {
    const cards = Object.assign({}, state.cards), pos = Object.assign({}, state.pos);
    let recent = state.recent;
    // 같은 질문을 다시 해서 같은 종류·같은 제목의 카드가 또 오면, 새로 쌓지 않고 기존 카드를 새 내용으로 바꾼다.
    const same = Object.keys(cards).find((k) => sameCard(cards[k], card));
    if (same) { card.id = same; if (els[same]) { els[same].remove(); delete els[same]; } }
    cards[card.id] = card;
    if (pos[card.id]) { state = { cards: cards, pos: pos, recent: recent }; return doFocus(card.id); }
    const fid = frontOf(pos);
    if (fid) {
      let free = SMALL_ORDER.find((s) => Object.keys(pos).every((k) => pos[k] !== s));
      if (!free) { // 자리가 없으면 가장 오래 전에 본 작은 카드를 치운다
        const smalls = Object.keys(pos).filter((k) => pos[k] !== 'front');
        const oldest = smalls.slice().sort((a, b) => recent.indexOf(b) - recent.indexOf(a))[0];
        free = pos[oldest]; delete pos[oldest]; delete cards[oldest];
        recent = recent.filter((x) => x !== oldest);
      }
      pos[fid] = free;
    }
    pos[card.id] = 'front';
    state = { cards: cards, pos: pos, recent: touch(recent, card.id) };
  }
  function doFocus(id) {
    const slot = state.pos[id];
    if (!slot || slot === 'front') return;
    const pos = Object.assign({}, state.pos), fid = frontOf(pos);
    pos[id] = 'front'; if (fid) pos[fid] = slot;
    state = { cards: state.cards, pos: pos, recent: touch(state.recent, id) };
  }
  function doClose(id) {
    if (!state.pos[id]) return;
    const was = state.pos[id] === 'front';
    const cards = Object.assign({}, state.cards), pos = Object.assign({}, state.pos);
    delete cards[id]; delete pos[id];
    const recent = state.recent.filter((x) => x !== id);
    if (was && recent.length) pos[recent[0]] = 'front';
    state = recent.length ? { cards: cards, pos: pos, recent: recent } : { cards: {}, pos: {}, recent: [] };
  }

  // ── 화면 ──
  const els = {}; // id -> 카드 요소 (바뀐 카드만 다시 그리지 않고 위치만 옮겨 전환 애니메이션이 이어진다)

  function build(card) {
    const el = h('div', 'rs-card' + (AMBER[card.type] ? ' amber' : ''));
    if (card.lang) el.lang = card.lang;
    el.setAttribute('role', 'button'); el.tabIndex = 0;
    const inner = h('div', 'rs-inner'), head = h('div', 'rs-head');
    head.appendChild(h('span', 'rs-kind', kindOf(card)));
    head.appendChild(h('h3', null, card.title || ''));
    const big = h('button', 'rs-big-btn', '⤢'); big.type = 'button'; big.title = ui(card).bigTip; big.setAttribute('aria-label', ui(card).big);
    big.addEventListener('click', (e) => { e.stopPropagation(); openBig(card.id); });
    head.appendChild(big);
    const x = h('button', 'rs-x', '×'); x.type = 'button'; x.setAttribute('aria-label', ui(card).close);
    x.addEventListener('click', (e) => { e.stopPropagation(); doClose(card.id); render(); });
    head.appendChild(x);
    const body = h('div', 'rs-body'); body.appendChild(cardBody(card));
    inner.appendChild(head); inner.appendChild(body); el.appendChild(inner);
    el.addEventListener('click', () => { doFocus(card.id); render(); });
    el.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); doFocus(card.id); render(); } });
    return el;
  }

  function fit() {
    if (!stage) return;
    const w = stage.getBoundingClientRect().width;
    stage.style.setProperty('--k', String(Math.max(0.5, Math.min(1, w / 860))));
  }

  function render() {
    const ids = Object.keys(state.cards);
    host.classList.toggle('has-cards', ids.length > 0);
    if (!ids.length) {
      if (stage) { stage.remove(); stage = null; }
      for (const k in els) delete els[k];
      return;
    }
    if (!stage) {
      stage = h('div', 'rs-stage');
      stage.addEventListener('click', onStageClick);
      host.appendChild(stage);
      if (window.ResizeObserver) new ResizeObserver(fit).observe(stage);
    }
    fit();
    for (const id in els) if (!state.cards[id]) { els[id].remove(); delete els[id]; }
    ids.forEach((id) => {
      const card = state.cards[id], slotName = state.pos[id], s = SLOTS[slotName], front = slotName === 'front';
      let el = els[id];
      if (!el) { el = els[id] = build(card); stage.appendChild(el); }
      el.classList.toggle('front', front);
      el.style.setProperty('--x', s.x); el.style.setProperty('--y', s.y); el.style.setProperty('--z', s.z);
      el.style.setProperty('--r', s.r + 'deg'); el.style.setProperty('--s', s.s);
      el.setAttribute('aria-label', (front ? ui(card).cur : ui(card).side) + kindOf(card) + ' ' + (card.title || '') + (front ? '' : ui(card).toFront));
      const x = el.querySelector('.rs-x'); if (x) x.style.display = front ? '' : 'none';
      const bb = el.querySelector('.rs-big-btn'); if (bb) bb.style.display = front ? '' : 'none';
    });
    if (bigId && !state.cards[bigId]) closeBig();
  }

  // ── 크게 보기: 화면 위에 큰 카드를 띄운다 (바깥을 누르거나 Esc / ×로 닫기) ──
  let bigId = null, bigEl = null, bigFrom = null;
  function openBig(id) {
    const card = state.cards[id]; if (!card) return;
    closeBig();
    bigId = id; bigFrom = document.activeElement;
    const back = h('div', 'rs-bigback');
    const box = h('div', 'rs-bigcard' + (AMBER[card.type] ? ' amber' : ''));
    box.setAttribute('role', 'dialog'); box.setAttribute('aria-modal', 'true'); if (card.lang) box.lang = card.lang;
    box.setAttribute('aria-label', ui(card).bigPre + (card.title || ''));
    const head = h('div', 'rs-head');
    head.appendChild(h('span', 'rs-kind', kindOf(card)));
    head.appendChild(h('h3', null, card.title || ''));
    const x = h('button', 'rs-x', '×'); x.type = 'button'; x.setAttribute('aria-label', ui(card).bigClose);
    x.addEventListener('click', closeBig); head.appendChild(x);
    const body = h('div', 'rs-body'); body.appendChild(cardBody(card));
    box.appendChild(head); box.appendChild(body); back.appendChild(box);
    box.addEventListener('click', (e) => e.stopPropagation());
    back.addEventListener('click', closeBig);
    document.body.appendChild(back); bigEl = back; x.focus();
  }
  function closeBig() {
    if (!bigEl) return;
    bigEl.remove(); bigEl = null; bigId = null;
    if (bigFrom && bigFrom.focus && document.contains(bigFrom)) bigFrom.focus();
    bigFrom = null;
  }

  // 3D로 기울어진 작은 카드는 브라우저가 클릭 대상으로 잡지 못하는 경우가 있어, 누른 위치로 직접 계산한다.
  function onStageClick(e) {
    if (e.target.closest('.rs-card')) return;
    const box = stage.getBoundingClientRect();
    const k = parseFloat(getComputedStyle(stage).getPropertyValue('--k')) || 1;
    const px = e.clientX - (box.left + box.width / 2), py = e.clientY - (box.top + box.height / 2);
    for (const id of Object.keys(state.cards)) {
      const name = state.pos[id], slot = SLOTS[name];
      if (!slot || name === 'front') continue;
      if (Math.abs(px - slot.x * k) < (CARD_W * slot.s * k) / 2 && Math.abs(py - slot.y * k) < (CARD_H * slot.s * k) / 2) { doFocus(id); render(); return; }
    }
  }

  function push(card) {
    if (!card || !card.type) return;
    card = Object.assign({}, card); card.id = card.id || 'c-' + Date.now() + '-' + (++seq);
    doPush(card); render();
  }
  function clear() { closeBig(); state = { cards: {}, pos: {}, recent: [] }; render(); }

  window.addEventListener('lumi:card', (e) => push(e.detail));
  window.addEventListener('lumi:cards-clear', clear);
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && bigEl) { closeBig(); return; } // 크게 보기부터 닫는다
    if (bigEl) return;
    if (!Object.keys(state.cards).length) return;
    if (e.target && /INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
    const at = (a, b) => Object.keys(state.pos).find((k) => state.pos[k] === a) || Object.keys(state.pos).find((k) => state.pos[k] === b);
    if (e.key === 'ArrowLeft') { const id = at('L1', 'L2'); if (id) { doFocus(id); render(); } }
    else if (e.key === 'ArrowRight') { const id = at('R1', 'R2'); if (id) { doFocus(id); render(); } }
    else if (e.key === 'Escape') clear();
  });

  window.LumiCards = {
    push: push, clear: clear,
    // "카드 닫아줘"처럼 사용자가 카드를 치우라고 한 말인지 (짧은 말만)
    isClearRequest: (msg) => msg.length <= 30 && /(카드|결과|띄운).{0,8}(닫|없애|지워|치워|숨겨|꺼)/.test(msg),
    has: () => Object.keys(state.cards).length > 0,
  };

  // ── 데모 (?demo=cards) ──
  if (/[?&]demo=cards/.test(location.search)) {
    let seed = 11; const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
    const days = []; let price = 70000;
    for (let i = 0; i < 24; i++) {
      const o = price, c = Math.round((o * (1 + (rnd() - 0.5) * 0.05)) / 100) * 100;
      days.push({ o: o, h: Math.round((Math.max(o, c) * (1 + rnd() * 0.012)) / 100) * 100, l: Math.round((Math.min(o, c) * (1 - rnd() * 0.012)) / 100) * 100, c: c, v: Math.round(800 + rnd() * 1400) });
      price = c;
    }
    const demo = {
      '일정 조회': { id: 'demo-sched', type: 'schedule', title: '오늘 14:00 구간', lead: '14:30–15:00, 두 일정이 30분 겹쳐요.', timeline: { from: 14, to: 16, items: [{ title: '인공지능 과제 마감', start: '14:00', end: '15:00' }, { title: '팀 프로젝트 회의', start: '14:30', end: '15:30', conflict: true }] }, note: '오늘 일정 5건 중 겹치는 2건만 보여주고 있어요.' },
      '웹 검색': { id: 'demo-search', type: 'search', title: 'multilingual-e5-small', lead: '여러 언어를 한 공간에 담는 문장 임베딩 모델이에요.', points: ['small 버전은 문장을 384차원 벡터로 바꿔요.', '입력 앞에 query: 접두어를 붙여서 써요.'], sources: ['Hugging Face 모델 카드', 'Multilingual E5 기술 보고서'] },
      '통계 (원형)': { id: 'demo-pie', type: 'stats', title: '이번 주 일정 비율', lead: '수업이 절반 가까이를 차지해요.', chart: { kind: 'pie', unit: '건', data: [['수업', 9], ['과제', 5], ['회의', 4], ['기타', 2]] }, note: '예시 데이터입니다.' },
      '통계 (막대)': { id: 'demo-bar', type: 'stats', title: '요일별 일정 수', lead: '수요일이 가장 빡빡해요.', chart: { kind: 'bar', unit: '건', data: [['월', 3], ['화', 2], ['수', 6], ['목', 3], ['금', 4], ['토', 1], ['일', 0]] }, note: '예시 데이터입니다.' },
      '주가 차트': { id: 'demo-stock', type: 'stock', title: '예시전자 (가상 종목)', chart: { kind: 'candle', unit: '원', data: days }, note: '가상으로 만든 예시 데이터입니다. 한국식 색(상승=빨강, 하락=파랑).' },
      '개념(수식·이미지)': { id: 'demo-concept', type: 'explain', lang: 'ko', title: '미분', lead: '순간 변화율을 구하는 방법이에요.', formulas: [{ tex: "f'(x)=\\lim_{h\\to 0}\\frac{f(x+h)-f(x)}{h}", caption: '도함수의 정의' }, { tex: '\\frac{d}{dx}x^{n}=nx^{n-1}', caption: '거듭제곱 미분법' }], detail: '미분은 함수의 그래프에서 한 점의 접선 기울기를 구하는 연산이에요. 속도는 위치의 미분, 가속도는 속도의 미분입니다.', points: ['접선의 기울기 = 순간 변화율', '적분과 서로 역연산 관계'], note: '예시 데이터입니다.' },
      '표': { id: 'demo-table', type: 'table', title: '임베딩 모델 비교', lead: '크기가 클수록 차원이 늘어요.', table: { cols: ['모델', '차원', '쓰임'], rows: [['e5-small', '384', 'Lumi 분류기'], ['e5-base', '768', '후보'], ['e5-large', '1024', '무거움']], highlight: 0 } },
    };
    const bar = h('div', 'rs-demo'); bar.setAttribute('role', 'group'); bar.setAttribute('aria-label', '데모 카드');
    bar.appendChild(h('span', null, 'DEMO'));
    Object.keys(demo).forEach((n) => { const b = h('button', null, n); b.type = 'button'; b.addEventListener('click', () => push(demo[n])); bar.appendChild(b); });
    const cb = h('button', 'clear', '카드 치우기'); cb.type = 'button'; cb.addEventListener('click', clear); bar.appendChild(cb);
    host.appendChild(bar);
  }
})();
