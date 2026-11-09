import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import katex from 'katex'
import 'katex/dist/katex.min.css'
import './ResultStage.css'
import { Bars, Candles, Donut, LineChart, Table, Timeline } from './resultCharts.jsx'

// Lumi가 정보를 검색해 보여주거나 일정 등을 조회할 때만 생기는 "결과 카드".
// 뉴런 코어를 기준으로 보고 있는 카드는 가운데에서 크게, 나머지는 코어 양옆에 작게 놓인다.
// 다른 카드로 바꾸면 보던 카드는 작아져서 양옆의 빈 자리로 가고, 사용자가 치우라고 하면 모두 사라진다.
//
// 카드 추가: window.dispatchEvent(new CustomEvent('lumi:card', { detail: card }))
// 모두 치우기: window.dispatchEvent(new CustomEvent('lumi:cards-clear'))
//   card = { id?, type, title, lead?, points?, sources?, timeline?, chart?, table?, note? }
//   chart = { kind: 'pie'|'bar'|'line'|'candle', ... }  (resultCharts.jsx 참고)
// 주소에 ?demo=cards 를 붙이면 코어 위쪽에 예시 카드를 띄우는 데모 버튼이 생긴다.

// 카드 겉 글자(종류 이름·버튼 설명 등)는 서버가 정해 준 card.lang(ko/en/ja/zh/es)에 맞춰 바뀐다.
const UI = {
  ko: { kind: { schedule: '일정', search: '웹 검색', mood: '감정 추이', reminder: '리마인더', video: '유튜브', stats: '통계', stock: '주가', table: '표', explain: '개념 설명', intent: '상대 말 의도' },
    source: '출처', big: '카드 크게 보기', bigTip: '크게 보기', close: '이 카드 닫기', bigClose: '크게 보기 닫기', bigPre: '크게 보기: ',
    cur: '현재 카드 ', side: '옆 카드 ', toFront: '. 누르면 앞으로 옵니다', formulas: '핵심 수식', imgAlt: '관련 이미지', open: '원문 보기' },
  en: { kind: { schedule: 'Schedule', search: 'Web search', mood: 'Mood trend', reminder: 'Reminder', video: 'YouTube', stats: 'Stats', stock: 'Stock', table: 'Table', explain: 'Concept', intent: 'Their intent' },
    source: 'Source', big: 'Enlarge card', bigTip: 'Enlarge', close: 'Close this card', bigClose: 'Close enlarged view', bigPre: 'Enlarged: ',
    cur: 'Current card ', side: 'Side card ', toFront: '. Press to bring it forward', formulas: 'Key formulas', imgAlt: 'Related image', open: 'Read more' },
  ja: { kind: { schedule: '予定', search: 'ウェブ検索', mood: '感情の推移', reminder: 'リマインダー', video: 'YouTube', stats: '統計', stock: '株価', table: '表', explain: '概念の説明', intent: '相手の意図' },
    source: '出典', big: 'カードを拡大', bigTip: '拡大', close: 'このカードを閉じる', bigClose: '拡大表示を閉じる', bigPre: '拡大: ',
    cur: '現在のカード ', side: '横のカード ', toFront: '。押すと前に出ます', formulas: '主な数式', imgAlt: '関連画像', open: '続きを読む' },
  zh: { kind: { schedule: '日程', search: '网页搜索', mood: '情绪趋势', reminder: '提醒', video: 'YouTube', stats: '统计', stock: '股价', table: '表格', explain: '概念讲解', intent: '对方意图' },
    source: '来源', big: '放大卡片', bigTip: '放大', close: '关闭此卡片', bigClose: '关闭放大视图', bigPre: '放大: ',
    cur: '当前卡片 ', side: '侧边卡片 ', toFront: '。点击可移到前面', formulas: '核心公式', imgAlt: '相关图片', open: '阅读原文' },
  es: { kind: { schedule: 'Agenda', search: 'Búsqueda web', mood: 'Estado de ánimo', reminder: 'Recordatorio', video: 'YouTube', stats: 'Estadísticas', stock: 'Bolsa', table: 'Tabla', explain: 'Concepto', intent: 'Intención' },
    source: 'Fuente', big: 'Ampliar tarjeta', bigTip: 'Ampliar', close: 'Cerrar esta tarjeta', bigClose: 'Cerrar vista ampliada', bigPre: 'Ampliada: ',
    cur: 'Tarjeta actual ', side: 'Tarjeta lateral ', toFront: '. Pulsa para traerla al frente', formulas: 'Fórmulas clave', imgAlt: 'Imagen relacionada', open: 'Leer más' },
}
const ui = (card) => UI[card && card.lang] || UI.ko
const kindOf = (card) => ui(card).kind[card.type] || card.type
const AMBER = new Set(['mood', 'reminder', 'stock'])

// 카드 원래 크기는 같고, 자리마다 이동·기울기·배율만 달라진다(작은 자리 배율 0.44).
export const CARD_W = 420
export const CARD_H = 460
const SMALL = 0.44
export const SLOTS = {
  front: { x: 0, y: 0, z: 60, r: 0, s: 1 },
  L1: { x: -335, y: -120, z: -90, r: 26, s: SMALL },
  R1: { x: 335, y: -120, z: -90, r: -26, s: SMALL },
  L2: { x: -335, y: 120, z: -90, r: 26, s: SMALL },
  R2: { x: 335, y: 120, z: -90, r: -26, s: SMALL },
}
const SMALL_ORDER = ['L1', 'R1', 'L2', 'R2']
const EMPTY = { cards: {}, pos: {}, recent: [] }

// 같은 카드인지: 종류와 제목이 같거나, 검색 카드끼리 출처가 절반 넘게 겹치면 같은 결과로 본다
// (Gemini가 google_search와 web_search를 연달아 불러 "의정부 날씨"/"의정부 날씨 예보"처럼 제목만 다른 카드가 오는 경우).
export function sameCard(a, b) {
  if (a.type !== b.type) return false
  if (a.title === b.title) return true
  if (a.type !== 'search' || !a.sources || !b.sources || !a.sources.length || !b.sources.length) return false
  const common = a.sources.filter((x) => b.sources.includes(x)).length
  return common / Math.min(a.sources.length, b.sources.length) > 0.5
}

function touch(recent, id) { return [id, ...recent.filter((x) => x !== id)] }

function reducer(state, action) {
  switch (action.type) {
    case 'push': {
      let card = action.card
      // 같은 질문을 다시 해서 같은 종류·같은 제목의 카드가 또 오면, 새로 쌓지 않고 기존 카드를 새 내용으로 바꾼다.
      const same = Object.keys(state.cards).find((k) => sameCard(state.cards[k], card))
      if (same) card = { ...card, id: same }
      const cards = { ...state.cards, [card.id]: card }
      const pos = { ...state.pos }
      let recent = state.recent
      if (pos[card.id]) {                       // 같은 카드를 다시 받으면 내용만 바꾸고 앞으로
        return reducer({ cards, pos, recent }, { type: 'focus', id: card.id })
      }
      const frontId = Object.keys(pos).find((k) => pos[k] === 'front')
      if (frontId) {
        let free = SMALL_ORDER.find((s) => !Object.values(pos).includes(s))
        if (!free) {                            // 자리가 없으면 가장 오래 전에 본 작은 카드를 치운다
          const smalls = Object.keys(pos).filter((k) => pos[k] !== 'front')
          const oldest = [...smalls].sort((a, b) => recent.indexOf(b) - recent.indexOf(a))[0]
          free = pos[oldest]
          delete pos[oldest]; delete cards[oldest]
          recent = recent.filter((x) => x !== oldest)
        }
        pos[frontId] = free
      }
      pos[card.id] = 'front'
      return { cards, pos, recent: touch(recent, card.id) }
    }
    case 'focus': {
      const slot = state.pos[action.id]
      if (!slot || slot === 'front') return state
      const frontId = Object.keys(state.pos).find((k) => state.pos[k] === 'front')
      const pos = { ...state.pos, [action.id]: 'front' }
      if (frontId) pos[frontId] = slot          // 보던 카드는 방금 비워진 자리로 간다
      return { ...state, pos, recent: touch(state.recent, action.id) }
    }
    case 'close': {
      if (!state.pos[action.id]) return state
      const wasFront = state.pos[action.id] === 'front'
      const cards = { ...state.cards }, pos = { ...state.pos }
      delete cards[action.id]; delete pos[action.id]
      const recent = state.recent.filter((x) => x !== action.id)
      if (wasFront && recent.length) pos[recent[0]] = 'front'
      return recent.length ? { cards, pos, recent } : EMPTY
    }
    case 'clear':
      return EMPTY
    default:
      return state
  }
}

// LaTeX → KaTeX. 서버가 한 번 거른 식이지만 화면에서도 trust:false·throwOnError:false로 한 번 더 막는다.
// KaTeX가 못 읽는 식이면 원문을 고정폭 글자로 보여 준다(textContent라 HTML로 해석되지 않는다).
function Tex({ tex }) {
  const ref = useRef(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    try {
      katex.render(String(tex), el, { displayMode: true, throwOnError: true, trust: false, strict: 'ignore', maxExpand: 200, output: 'htmlAndMathml' })
      setFailed(false)
    } catch { setFailed(true) }
  }, [tex])
  return failed ? <code className="rs-tex-raw">{tex}</code> : <div className="rs-tex" ref={ref} />
}

// 서버가 돌려준 이미지 주소는 우리 서버의 /api/media/ 프록시일 때만 <img>로 쓴다.
const okImg = (u) => typeof u === 'string' && /^\/api\/media\/[\w/.-]+$/.test(u)
const okPage = (u) => { try { const x = new URL(u); return x.protocol === 'https:' && /(^|\.)wikipedia\.org$/.test(x.hostname) } catch { return false } }

function Figure({ card }) {
  const im = card.image, t = ui(card)
  if (!im) return null
  const link = okPage(im.page)
  return (
    <figure className="rs-fig">
      {okImg(im.url) && <img src={im.url} alt={im.caption || t.imgAlt} loading="lazy" referrerPolicy="no-referrer" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      <figcaption>
        {im.caption && <span>{im.caption}</span>}
        {im.credit && <em>{im.credit}</em>}
        {link && <a href={im.page} target="_blank" rel="noopener noreferrer">{t.open} ↗</a>}
      </figcaption>
    </figure>
  )
}

// 함수 그래프: 서버가 식을 계산해 보낸 점 [{name, pts:[[x,y],...]}]을 여러 선으로 그린다. 숫자만 받아 SVG로 그리므로 HTML로 해석되지 않는다.
const PLOT_COLORS = ['#3FE0FF', '#FFA94D', '#B6F36B']
function Plot({ chart }) {
  const series = (chart.data || []).filter((s) => s && Array.isArray(s.pts) && s.pts.length > 1).slice(0, 3)
  if (!series.length) return null
  const all = series.flatMap((s) => s.pts.filter((p) => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1])))
  if (all.length < 2) return null
  const W = 360, H = 200, L = 34, R = 10, T = 14, B = 24
  let xmin = Math.min(...all.map((p) => p[0])), xmax = Math.max(...all.map((p) => p[0]))
  let ymin = Math.min(...all.map((p) => p[1])), ymax = Math.max(...all.map((p) => p[1]))
  if (ymax - ymin < 1e-9) { ymax += 1; ymin -= 1 }
  const padY = (ymax - ymin) * 0.08; ymin -= padY; ymax += padY
  const X = (x) => L + ((x - xmin) / (xmax - xmin || 1)) * (W - L - R)
  const Y = (y) => T + (1 - (y - ymin) / (ymax - ymin)) * (H - T - B)
  const fmt = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2)).replace(/\.?0+$/, '')
  const zeroX = xmin < 0 && xmax > 0, zeroY = ymin < 0 && ymax > 0
  return (
    <div className="rs-plot">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={series.map((s) => s.name).join(', ')}>
        <rect x={L} y={T} width={W - L - R} height={H - T - B} fill="none" stroke="rgba(63,224,255,.25)" />
        {zeroY && <line x1={L} x2={W - R} y1={Y(0)} y2={Y(0)} stroke="rgba(255,255,255,.35)" strokeDasharray="3 3" />}
        {zeroX && <line y1={T} y2={H - B} x1={X(0)} x2={X(0)} stroke="rgba(255,255,255,.35)" strokeDasharray="3 3" />}
        {series.map((s, i) => (
          <polyline key={i} fill="none" stroke={PLOT_COLORS[i]} strokeWidth="2" strokeLinejoin="round"
            points={s.pts.filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1])).map((p) => `${X(p[0]).toFixed(1)},${Y(p[1]).toFixed(1)}`).join(' ')} />
        ))}
        {[[xmin, 'start'], [xmax, 'end']].map(([v, a]) => (
          <text key={a} x={X(v)} y={H - 8} textAnchor={a} fontSize="10" fill="#7F9AA8">{fmt(v)}</text>
        ))}
        {[ymin + padY, ymax - padY].map((v, i) => (
          <text key={i} x={L - 4} y={Y(v) + 3} textAnchor="end" fontSize="10" fill="#7F9AA8">{fmt(v)}</text>
        ))}
      </svg>
      <div className="rs-legend">
        {series.map((s, i) => <span key={i}><i style={{ background: PLOT_COLORS[i] }} />{String(s.name).slice(0, 40)}</span>)}
      </div>
    </div>
  )
}

const LINK_HOSTS = new Set(['map.kakao.com', 'map.naver.com', 'www.google.com', 'ko.wikipedia.org', 'en.wikipedia.org', 'earth.nullschool.net'])
const okLink = (u) => { try { const x = new URL(u); return x.protocol === 'https:' && LINK_HOSTS.has(x.hostname) && (x.hostname !== 'www.google.com' || x.pathname.startsWith('/maps')) } catch { return false } }

function CardBody({ card }) {
  const t = ui(card)
  const formulas = Array.isArray(card.formulas) ? card.formulas.filter((f) => f && typeof f.tex === 'string') : []
  return (
    <>
      {card.lead && <p className="rs-lead">{card.lead}</p>}
      {formulas.length > 0 && (
        <div className="rs-formulas" aria-label={t.formulas}>
          {formulas.map((f, i) => (
            <div className="rs-formula" key={i + f.tex}>
              <Tex tex={f.tex} />
              {f.caption && <p>{f.caption}</p>}
            </div>
          ))}
        </div>
      )}
      <Figure card={card} />
      {card.detail && <p className="rs-detail">{card.detail}</p>}
      {card.timeline && <Timeline tl={card.timeline} />}
      {card.chart && card.chart.kind === 'pie' && <Donut chart={card.chart} />}
      {card.chart && card.chart.kind === 'bar' && <Bars chart={card.chart} />}
      {card.chart && card.chart.kind === 'line' && <LineChart chart={card.chart} />}
      {card.chart && card.chart.kind === 'candle' && <Candles chart={card.chart} />}
      {card.chart && card.chart.kind === 'plot' && <Plot chart={card.chart} />}
      {card.table && <Table table={card.table} />}
      {card.points && <ul className="rs-points">{card.points.map((p) => <li key={p}>{p}</li>)}</ul>}
      {card.sources && <div className="rs-sources">{card.sources.map((s, i) => <div key={s}><span>{s}</span><em>{t.source} {i + 1}</em></div>)}</div>}
      {Array.isArray(card.links) && card.links.some((l) => l && okLink(l.url)) && (
        <div className="rs-links">
          {card.links.filter((l) => l && okLink(l.url)).slice(0, 4).map((l, i) => (
            <a key={i} href={l.url} target="_blank" rel="noopener noreferrer">{String(l.label).slice(0, 40)} ↗</a>
          ))}
        </div>
      )}
      {card.note && <p className="rs-note">{card.note}</p>}
      {card.action && card.action.label && card.action.event && (
        <button type="button" className="rs-action"
          onClick={(e) => { e.stopPropagation(); window.dispatchEvent(new CustomEvent('lumi:card-action', { detail: { id: card.id, event: String(card.action.event), data: String(card.action.data || '').slice(0, 64) } })) }}>
          {String(card.action.label).slice(0, 20)}
        </button>
      )}
    </>
  )
}

export default function ResultStage({ onOpenChange }) {
  const demo = typeof window !== 'undefined' && /[?&]demo=cards/.test(window.location.search)
  const [state, dispatch] = useReducer(reducer, EMPTY)
  const stageRef = useRef(null)
  const [bigId, setBigId] = useState(null)      // 크게 보기 중인 카드 id
  const ids = Object.keys(state.cards)
  const hasCards = ids.length > 0

  const push = useCallback((card) => {
    if (!card || !card.type) return
    dispatch({ type: 'push', card: { ...card, id: card.id || 'c-' + Date.now() + Math.random().toString(36).slice(2, 6) } })
  }, [])

  useEffect(() => {
    const onCard = (e) => push(e.detail)
    const onClear = () => dispatch({ type: 'clear' })
    window.addEventListener('lumi:card', onCard)
    window.addEventListener('lumi:cards-clear', onClear)
    return () => { window.removeEventListener('lumi:card', onCard); window.removeEventListener('lumi:cards-clear', onClear) }
  }, [push])

  useEffect(() => { onOpenChange?.(hasCards) }, [hasCards, onOpenChange])
  useEffect(() => { if (bigId && !state.cards[bigId]) setBigId(null) }, [bigId, state.cards])

  // 코어 영역 폭에 맞춰 카드 배치를 줄여서 양옆 패널을 침범하지 않게 한다.
  useEffect(() => {
    const el = stageRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([entry]) => {
      const w = entry.contentRect.width
      el.style.setProperty('--k', String(Math.max(0.5, Math.min(1, w / 860))))
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [hasCards])

  useEffect(() => {
    if (!hasCards) return
    const key = (e) => {
      if (e.key === 'Escape' && bigId) { setBigId(null); return }   // 크게 보기부터 닫는다
      if (bigId) return
      if (e.target && /INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return
      const at = (a, b) => Object.keys(state.pos).find((k) => state.pos[k] === a) || Object.keys(state.pos).find((k) => state.pos[k] === b)
      if (e.key === 'ArrowLeft') { const id = at('L1', 'L2'); if (id) dispatch({ type: 'focus', id }) }
      else if (e.key === 'ArrowRight') { const id = at('R1', 'R2'); if (id) dispatch({ type: 'focus', id }) }
      else if (e.key === 'Escape') dispatch({ type: 'clear' })
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [hasCards, state.pos, bigId])

  // 3D로 기울어진 작은 카드는 브라우저가 클릭 대상으로 잡지 못하는 경우가 있어서,
  // 빈 곳을 누른 위치가 어느 자리의 카드 안쪽인지 직접 계산한다.
  function onTrackClick(e) {
    if (e.target.closest('.rs-card')) return
    const box = e.currentTarget.getBoundingClientRect()
    const k = parseFloat(getComputedStyle(stageRef.current).getPropertyValue('--k')) || 1
    const px = e.clientX - (box.left + box.width / 2), py = e.clientY - (box.top + box.height / 2)
    for (const id of ids) {
      const slot = SLOTS[state.pos[id]]
      if (!slot || state.pos[id] === 'front') continue
      if (Math.abs(px - slot.x * k) < (CARD_W * slot.s * k) / 2 && Math.abs(py - slot.y * k) < (CARD_H * slot.s * k) / 2) {
        dispatch({ type: 'focus', id }); return
      }
    }
  }

  const demoCards = demo ? makeDemoCards() : null

  return (
    <>
      {demo && (
        <div className="rs-demo" role="group" aria-label="데모 카드">
          <span>DEMO</span>
          {Object.entries(demoCards).map(([name, card]) => (
            <button key={name} type="button" onClick={() => push(card)}>{name}</button>
          ))}
          <button type="button" className="clear" onClick={() => dispatch({ type: 'clear' })}>카드 치우기</button>
        </div>
      )}
      {hasCards && (
        <div className="rs-stage" ref={stageRef} onClick={onTrackClick}>
          {ids.map((id) => {
            const card = state.cards[id], slotName = state.pos[id], s = SLOTS[slotName], front = slotName === 'front'
            return (
              <div
                key={id}
                role="button"
                tabIndex={0}
                lang={card.lang || undefined}
                className={'rs-card' + (front ? ' front' : '') + (AMBER.has(card.type) ? ' amber' : '')}
                style={{ '--x': s.x, '--y': s.y, '--z': s.z, '--r': s.r + 'deg', '--s': s.s }}
                onClick={() => dispatch({ type: 'focus', id })}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); dispatch({ type: 'focus', id }) } }}
                aria-label={(front ? ui(card).cur : ui(card).side) + kindOf(card) + ' ' + card.title + (front ? '' : ui(card).toFront)}
              >
                <div className="rs-inner">
                  <div className="rs-head">
                    <span className="rs-kind">{kindOf(card)}</span>
                    <h3>{card.title}</h3>
                    {front && (
                      <button type="button" className="rs-big-btn" aria-label={ui(card).big} title={ui(card).bigTip} onClick={(e) => { e.stopPropagation(); setBigId(id) }}>⤢</button>
                    )}
                    {front && (
                      <button type="button" className="rs-x" aria-label={ui(card).close} onClick={(e) => { e.stopPropagation(); dispatch({ type: 'close', id }) }}>×</button>
                    )}
                  </div>
                  <div className="rs-body"><CardBody card={card} /></div>
                </div>
              </div>
            )
          })}
        </div>
      )}
      {bigId && state.cards[bigId] && createPortal(
        <div className="rs-bigback" onClick={() => setBigId(null)}>
          <div lang={state.cards[bigId].lang || undefined} className={'rs-bigcard' + (AMBER.has(state.cards[bigId].type) ? ' amber' : '')} role="dialog" aria-modal="true"
            aria-label={ui(state.cards[bigId]).bigPre + state.cards[bigId].title} onClick={(e) => e.stopPropagation()}>
            <div className="rs-head">
              <span className="rs-kind">{kindOf(state.cards[bigId])}</span>
              <h3>{state.cards[bigId].title}</h3>
              <button type="button" className="rs-x" aria-label={ui(state.cards[bigId]).bigClose} autoFocus onClick={() => setBigId(null)}>×</button>
            </div>
            <div className="rs-body"><CardBody card={state.cards[bigId]} /></div>
          </div>
        </div>,
        document.body
      )}
    </>
  )
}

// ── 데모용 예시 데이터(실제 Lumi 응답이 아님) ──
function makeDemoCards() {
  let seed = 11
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 }
  const days = []
  let price = 70000
  for (let i = 0; i < 24; i++) {
    const o = price, c = Math.round((o * (1 + (rnd() - 0.5) * 0.05)) / 100) * 100
    const h = Math.round((Math.max(o, c) * (1 + rnd() * 0.012)) / 100) * 100
    const l = Math.round((Math.min(o, c) * (1 - rnd() * 0.012)) / 100) * 100
    days.push({ o, h, l, c, v: Math.round(800 + rnd() * 1400) })
    price = c
  }
  return {
    '일정 조회': {
      id: 'demo-sched', type: 'schedule', title: '오늘 14:00 구간', lead: '14:30–15:00, 두 일정이 30분 겹쳐요.',
      timeline: { from: 14, to: 16, items: [{ title: '인공지능 과제 마감', start: '14:00', end: '15:00' }, { title: '팀 프로젝트 회의', start: '14:30', end: '15:30', conflict: true }] },
      note: '오늘 일정 5건 중 겹치는 2건만 보여주고 있어요.',
    },
    '웹 검색': {
      id: 'demo-search', type: 'search', title: 'multilingual-e5-small', lead: '여러 언어를 한 공간에 담는 문장 임베딩 모델이에요.',
      points: ['small 버전은 문장을 384차원 벡터로 바꿔요.', '입력 앞에 query: 접두어를 붙여서 써요.'],
      sources: ['Hugging Face 모델 카드', 'Multilingual E5 기술 보고서'],
    },
    '통계 (원형)': {
      id: 'demo-pie', type: 'stats', title: '이번 주 일정 비율', lead: '수업이 절반 가까이를 차지해요.',
      chart: { kind: 'pie', unit: '건', data: [['수업', 9], ['과제', 5], ['회의', 4], ['기타', 2]] },
      note: '예시 데이터입니다.',
    },
    '통계 (막대)': {
      id: 'demo-bar', type: 'stats', title: '요일별 일정 수', lead: '수요일이 가장 빡빡해요.',
      chart: { kind: 'bar', unit: '건', data: [['월', 3], ['화', 2], ['수', 6], ['목', 3], ['금', 4], ['토', 1], ['일', 0]] },
      note: '예시 데이터입니다.',
    },
    '주가 차트': {
      id: 'demo-stock', type: 'stock', title: '예시전자 (가상 종목)', lead: null,
      chart: { kind: 'candle', unit: '원', data: days },
      note: '가상으로 만든 예시 데이터입니다. 한국식 색(상승=빨강, 하락=파랑).',
    },
    '개념(수식·이미지)': {
      id: 'demo-concept', type: 'explain', lang: 'ko', title: '미분', lead: '순간 변화율을 구하는 방법이에요.',
      formulas: [{ tex: "f'(x)=\\lim_{h\\to 0}\\frac{f(x+h)-f(x)}{h}", caption: '도함수의 정의' }, { tex: '\\frac{d}{dx}x^{n}=nx^{n-1}', caption: '거듭제곱 미분법' }],
      detail: '미분은 함수의 그래프에서 한 점의 접선 기울기를 구하는 연산이에요. 속도는 위치의 미분, 가속도는 속도의 미분입니다.',
      points: ['접선의 기울기 = 순간 변화율', '적분과 서로 역연산 관계'], note: '예시 데이터입니다.',
    },
    '표': {
      id: 'demo-table', type: 'table', title: '임베딩 모델 비교', lead: '크기가 클수록 차원이 늘어요.',
      table: { cols: ['모델', '차원', '쓰임'], rows: [['e5-small', '384', 'Lumi 분류기'], ['e5-base', '768', '후보'], ['e5-large', '1024', '무거움']], highlight: 0 },
    },
  }
}
