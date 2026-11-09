// 결과 카드 안에 그려지는 차트들(의존 라이브러리 없이 SVG). 색은 Lumi HUD 팔레트를 따른다.
const PALETTE = ['#3FE0FF', '#FFA94D', '#7C9CFF', '#5EE6A8', '#FF6A6E']
const UP = '#FF5C6A', DOWN = '#4D9CFF' // 한국 주식 관행: 상승=빨강, 하락=파랑
const GRID = 'rgba(63,224,255,0.18)', MUTED = '#6F8896'
const fmt = (n) => Number(n).toLocaleString('ko-KR')

function toMin(hhmm) { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m }

export function Timeline({ tl }) {
  const start = tl.from * 60, span = (tl.to - tl.from) * 60
  const pos = (hhmm) => ((toMin(hhmm) - start) / span) * 100
  const ticks = []
  for (let h = tl.from; h < tl.to; h++) ticks.push(h)
  const hasConflict = tl.items.some((i) => i.conflict)
  const ovStart = Math.max(...tl.items.map((i) => toMin(i.start)))
  const ovEnd = Math.min(...tl.items.map((i) => toMin(i.end)))
  return (
    <div className="rs-tl" style={{ height: 40 + tl.items.length * 46 }}>
      {ticks.map((h) => <div className="rs-tick" key={h} style={{ left: ((h * 60 - start) / span) * 100 + '%' }}><span>{h}:00</span></div>)}
      {hasConflict && ovEnd > ovStart && <div className="rs-overlap" style={{ left: ((ovStart - start) / span) * 100 + '%', width: ((ovEnd - ovStart) / span) * 100 + '%' }} />}
      {tl.items.map((it, i) => (
        <div key={it.title} className={'rs-blk ' + (it.conflict ? 'warn' : '')} style={{ left: pos(it.start) + '%', width: pos(it.end) - pos(it.start) + '%', top: 30 + i * 46 }}>
          <b>{it.title}</b><small>{it.start}–{it.end}</small>
        </div>
      ))}
    </div>
  )
}

export function Donut({ chart }) {
  const total = chart.data.reduce((s, d) => s + d[1], 0)
  const R = 62, C = 2 * Math.PI * R
  let acc = 0
  return (
    <div className="rs-donut">
      <svg viewBox="0 0 160 160" role="img" aria-label={'원형 그래프: ' + chart.data.map((d) => d[0] + ' ' + d[1]).join(', ')}>
        <circle cx="80" cy="80" r={R} fill="none" stroke="rgba(63,224,255,0.08)" strokeWidth="24" />
        {chart.data.map((d, i) => {
          const len = (d[1] / total) * C
          const el = <circle key={d[0]} cx="80" cy="80" r={R} fill="none" stroke={PALETTE[i % PALETTE.length]} strokeWidth="24"
            strokeDasharray={`${Math.max(0, len - 2)} ${C - Math.max(0, len - 2)}`} strokeDashoffset={-acc} transform="rotate(-90 80 80)" />
          acc += len
          return el
        })}
        <text x="80" y="78" textAnchor="middle" fontSize="22" fill="#fff" fontFamily="Orbitron, sans-serif" fontWeight="700">{total}</text>
        <text x="80" y="96" textAnchor="middle" fontSize="10" fill={MUTED}>전체 {chart.unit || ''}</text>
      </svg>
      <ul className="rs-legend">
        {chart.data.map((d, i) => (
          <li key={d[0]}><i style={{ background: PALETTE[i % PALETTE.length] }} /><span>{d[0]}</span><b>{Math.round((d[1] / total) * 100)}%</b></li>
        ))}
      </ul>
    </div>
  )
}

export function Bars({ chart }) {
  const W = 360, H = 190, padB = 22, padT = 18
  const max = Math.max(1, ...chart.data.map((d) => d[1]))
  const bw = (W - 20) / chart.data.length
  const best = chart.data.reduce((a, d, i) => (d[1] > chart.data[a][1] ? i : a), 0)
  return (
    <svg className="rs-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="막대 그래프">
      {[0, 0.5, 1].map((t) => <line key={t} x1="0" x2={W} y1={padT + (H - padT - padB) * (1 - t)} y2={padT + (H - padT - padB) * (1 - t)} stroke={GRID} strokeDasharray="3 4" />)}
      {chart.data.map((d, i) => {
        const h = ((H - padT - padB) * d[1]) / max, x = 10 + i * bw + bw * 0.18
        return (
          <g key={d[0]}>
            <rect x={x} y={H - padB - h} width={bw * 0.64} height={Math.max(h, 1)} fill={i === best ? '#FFA94D' : '#3FE0FF'} fillOpacity={i === best ? 1 : 0.65} />
            <text x={x + bw * 0.32} y={H - padB - h - 5} textAnchor="middle" fontSize="11" fill="#E4F8FF" fontFamily="Share Tech Mono, monospace">{d[1]}</text>
            <text x={x + bw * 0.32} y={H - 6} textAnchor="middle" fontSize="11" fill={MUTED}>{d[0]}</text>
          </g>
        )
      })}
    </svg>
  )
}

export function LineChart({ chart }) {
  const W = 360, H = 190, padB = 22, padT = 14, vals = chart.data.map((d) => d[1])
  const lo = Math.min(...vals), hi = Math.max(...vals), rng = hi - lo || 1
  const X = (i) => 8 + (i * (W - 16)) / Math.max(1, vals.length - 1)
  const Y = (v) => padT + (H - padT - padB) * (1 - (v - lo) / rng)
  const pts = vals.map((v, i) => `${X(i)},${Y(v)}`).join(' ')
  return (
    <svg className="rs-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="선 그래프">
      {[0, 0.5, 1].map((t) => <line key={t} x1="0" x2={W} y1={padT + (H - padT - padB) * (1 - t)} y2={padT + (H - padT - padB) * (1 - t)} stroke={GRID} strokeDasharray="3 4" />)}
      <polygon points={`${X(0)},${H - padB} ${pts} ${X(vals.length - 1)},${H - padB}`} fill="#3FE0FF" fillOpacity="0.12" />
      <polyline points={pts} fill="none" stroke="#3FE0FF" strokeWidth="2" />
      <circle cx={X(vals.length - 1)} cy={Y(vals[vals.length - 1])} r="4" fill="#FFA94D" />
      {chart.data.map((d, i) => (i % Math.ceil(vals.length / 6) === 0 ? <text key={d[0]} x={X(i)} y={H - 6} textAnchor="middle" fontSize="10" fill={MUTED}>{d[0]}</text> : null))}
    </svg>
  )
}

export function Candles({ chart }) {
  const d = chart.data
  const W = 360, H = 250, volH = 44, top = 8, priceH = H - volH - 22
  const hi = Math.max(...d.map((x) => x.h)), lo = Math.min(...d.map((x) => x.l)), rng = hi - lo || 1
  const vmax = Math.max(...d.map((x) => x.v))
  const step = (W - 16) / d.length, bw = step * 0.62
  const Y = (v) => top + priceH * (1 - (v - lo) / rng)
  const cx = (i) => 8 + i * step + step / 2
  const ma = d.map((_, i) => (i < 4 ? null : d.slice(i - 4, i + 1).reduce((s, x) => s + x.c, 0) / 5))
  const maPts = ma.map((v, i) => (v == null ? null : `${cx(i)},${Y(v)}`)).filter(Boolean).join(' ')
  const last = d[d.length - 1], prev = d[d.length - 2]
  const diff = last.c - prev.c, pct = (diff / prev.c) * 100
  const col = diff >= 0 ? UP : DOWN
  return (
    <div className="rs-candle">
      <div className="rs-quote">
        <b style={{ color: col }}>{fmt(last.c)}<small>{chart.unit || ''}</small></b>
        <span style={{ color: col }}>{diff >= 0 ? '▲' : '▼'} {fmt(Math.abs(diff))} ({pct >= 0 ? '+' : ''}{pct.toFixed(2)}%)</span>
      </div>
      <svg className="rs-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="주가 캔들 차트">
        {[0, 0.5, 1].map((t) => (
          <g key={t}>
            <line x1="0" x2={W} y1={top + priceH * t} y2={top + priceH * t} stroke={GRID} strokeDasharray="3 4" />
            <text x={W - 2} y={top + priceH * t - 3} textAnchor="end" fontSize="9" fill={MUTED} fontFamily="Share Tech Mono, monospace">{fmt(Math.round(hi - rng * t))}</text>
          </g>
        ))}
        {d.map((x, i) => {
          const c = x.c >= x.o ? UP : DOWN
          return (
            <g key={i}>
              <line x1={cx(i)} x2={cx(i)} y1={Y(x.h)} y2={Y(x.l)} stroke={c} />
              <rect x={cx(i) - bw / 2} y={Math.min(Y(x.o), Y(x.c))} width={bw} height={Math.max(1.5, Math.abs(Y(x.o) - Y(x.c)))} fill={c} />
              <rect x={cx(i) - bw / 2} y={H - 20 - (x.v / vmax) * volH} width={bw} height={(x.v / vmax) * volH} fill={c} fillOpacity="0.45" />
            </g>
          )
        })}
        <polyline points={maPts} fill="none" stroke="#3FE0FF" strokeWidth="1.4" />
        <text x="10" y={H - 4} fontSize="9" fill={MUTED}>거래량</text>
        <text x={W - 2} y={H - 4} textAnchor="end" fontSize="9" fill="#3FE0FF">5일 이동평균</text>
      </svg>
    </div>
  )
}

export function Table({ table }) {
  return (
    <table className="rs-table">
      <thead><tr>{table.cols.map((c) => <th key={c}>{c}</th>)}</tr></thead>
      <tbody>{table.rows.map((r, i) => <tr key={i} className={i === table.highlight ? 'hl' : ''}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
    </table>
  )
}
