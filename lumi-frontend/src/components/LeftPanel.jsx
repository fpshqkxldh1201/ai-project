import { useState } from 'react'
import { useLumi } from '../context/LumiContext.jsx'

const FUNCTIONS = ['일정 관리', '리마인더', '웹 검색']
const SEC_OPEN_KEY = 'lumi.secOpen'
function loadSecOpen() {
  try { return localStorage.getItem(SEC_OPEN_KEY) === '1' } catch { return false }
}

const LEVEL_LABEL = { SECURE: 'SECURE', NOTICE: 'NOTICE', ALERT: 'ALERT' }

// 서버가 주는 LocalDateTime(타임존 없는 ISO 문자열) → 'MM/DD HH:mm'
function formatTime(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const z = (n) => String(n).padStart(2, '0')
  return z(d.getMonth() + 1) + '/' + z(d.getDate()) + ' ' + z(d.getHours()) + ':' + z(d.getMinutes())
}

export default function LeftPanel() {
  const { usage, moodDays, emotionLogEnabled, secStatus } = useLumi()
  const [secOpen, setSecOpen] = useState(loadSecOpen)
  function toggleSec() {
    const next = !secOpen
    setSecOpen(next)
    try { localStorage.setItem(SEC_OPEN_KEY, next ? '1' : '0') } catch { /* 저장 불가 환경이면 이번 화면에서만 유지 */ }
  }
  const days = usage.last7Days || []
  const maxCount = Math.max(1, ...days.map((d) => d.count))
  const todayStr = new Date().toISOString().slice(0, 10)
  const logged = moodDays.filter((d) => d.count > 0)
  const total = logged.reduce((sum, d) => sum + d.count, 0)
  const avg = total ? logged.reduce((sum, d) => sum + d.avgValence * d.count, 0) / total : null

  return (
    <section className="hud-panel">
      <div className="panel-header"><span>AVAILABLE FUNCTIONS</span><span className="tag">ONLINE</span></div>
      <div className="fn-list">
        {FUNCTIONS.map((name) => (
          <div className="fn-row" key={name}>
            <span className="fn-name">{name}</span>
            <span className="fn-state"><span className="dot"></span>ONLINE</span>
          </div>
        ))}
      </div>

      <div className="chart-block">
        <div className="panel-header" style={{ marginBottom: 12 }}><span>API USAGE · 7 DAYS</span></div>
        <div className="readout">
          <span className="num">{usage.today} / {usage.dailyLimit}</span>
          <span className="lbl">TODAY</span>
        </div>
        <div className="bar-chart">
          {days.map((d) => {
            const pct = Math.max(4, Math.round((d.count / maxCount) * 100))
            return <div key={d.date} className={'bar' + (d.date === todayStr ? ' today' : '')} style={{ height: pct + '%' }} />
          })}
        </div>
        <div className="bar-labels">
          {days.map((d) => <span key={d.date}>{d.label}</span>)}
        </div>
      </div>

      <div className="chart-block">
        <div className="panel-header" style={{ marginBottom: 12 }}>
          <span>MOOD · 7 DAYS</span>
          <span className="tag">{avg === null ? '—' : (avg > 0 ? '+' : '') + avg.toFixed(2)}</span>
        </div>
        <div className="mood-chart">
          {moodDays.map((d) => {
            const v = d.avgValence
            const cls = d.count === 0 ? 'none' : v >= 0 ? 'pos' : 'neg'
            const h = d.count === 0 ? 0 : Math.max(6, Math.round(Math.min(1, Math.abs(v)) * 50))
            const tip = d.count === 0
              ? d.date + ' · 기록 없음'
              : d.date + ' · 평균 ' + (v > 0 ? '+' : '') + v.toFixed(2) + ' · ' + d.count + '회' + (d.topMood ? ' · ' + d.topMood : '')
            return (
              <div key={d.date} className={'mood-col' + (d.date === todayStr ? ' today' : '')} title={tip}>
                <div className={'mood-bar ' + cls} style={d.count === 0 ? undefined : { height: h + '%' }} />
              </div>
            )
          })}
        </div>
        <div className="bar-labels">
          {moodDays.map((d) => <span key={d.date}>{d.label}</span>)}
        </div>
        {total === 0 && (
          <div className="mood-empty">
            {emotionLogEnabled ? '대화하면 감정 흐름이 여기에 쌓여요' : '감정 기록이 꺼져 있어요(설정에서 켤 수 있어요)'}
          </div>
        )}
      </div>

      <div className="chart-block">
        <div
          className="panel-header sec-toggle" style={{ marginBottom: 10 }} role="button" tabIndex={0}
          aria-expanded={secOpen} title={secOpen ? '접기' : '펼치기'}
          onClick={toggleSec}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSec() } }}
        >
          <span><span className="arrow">{secOpen ? '▾' : '▸'}</span>SECURITY STATUS</span>
          <span className="tag">24H</span>
        </div>
        <div className="sec-head">
          <span className={'sec-badge ' + (secStatus ? secStatus.level.toLowerCase() : 'unknown')}>
            {secStatus ? LEVEL_LABEL[secStatus.level] || secStatus.level : '—'}
          </span>
          <span className="sec-headline">{secStatus ? secStatus.headline : '보안 상태를 불러오는 중...'}</span>
        </div>
        <div className="sec-body" hidden={!secOpen}>
        {secStatus && secStatus.categories.length > 0 && (
          <div className="sec-cats">
            {secStatus.categories.map((c) => (
              <span className="sec-chip" key={c.key}>{c.label}<b>{c.count}</b></span>
            ))}
          </div>
        )}
        {secStatus && (
          <details className="sec-detail">
            <summary>방어 장치 {secStatus.protections.filter((p) => p.on).length}/{secStatus.protections.length} 작동 중</summary>
            {secStatus.protections.map((p) => (
              <div className="sec-prot" key={p.key}>
                <span className="nm">{p.label}<span className="nt">{p.note}</span></span>
                <span className={'st ' + (p.on ? 'on' : 'off')}>{p.on ? 'ON' : 'OFF'}</span>
              </div>
            ))}
            {secStatus.recent.length > 0 && <div className="sec-recent" style={{ marginTop: 8 }}><span>최근 기록</span></div>}
            {secStatus.recent.map((r, i) => (
              <div className="sec-recent" key={i}>
                <span>{r.label}</span>
                <span>{formatTime(r.occurredAt)}</span>
              </div>
            ))}
          </details>
        )}
        </div>
      </div>
    </section>
  )
}
