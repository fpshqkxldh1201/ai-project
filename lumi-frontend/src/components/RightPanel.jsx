import { useEffect, useState } from 'react'
import { pad } from '../hooks/useClock.js'
import { useLumi } from '../context/LumiContext.jsx'
import { countdownText, reminderWhen, weekBuckets } from '../utils/dashboardTime.js'

const SCHEDULE_ROWS = 6 // 패널에 보여줄 일정 개수(나머지는 '외 N건')
const REMINDER_ROWS = 4 // 패널에 보여줄 개수(나머지는 '외 N건')

export default function RightPanel() {
  const { schedules, reminders, notes, addNote, deleteNote, clearNotes } = useLumi()
  const [noteText, setNoteText] = useState('')
  // 남은 시간 표시가 가만히 있어도 줄어들도록 30초마다 현재 시각을 갱신
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30000)
    return () => clearInterval(id)
  }, [])

  async function submitNote() {
    if (await addNote(noteText)) setNoteText('')
  }
  const { rows, count, conflictAlert } = schedules
  const loaded = count !== null

  // ── TODAY AT A GLANCE ──
  const todayKey = weekBuckets([], now)[0].key
  const nextRow = rows.find((r) => r._start >= now)
  const todayCount = weekBuckets(rows, now)[0].count
  const conflictCount = rows.filter((r) => r.hasConflict).length
  const reminderCount = reminders === null ? null : reminders.length
  const glance = [
    { key: 'next', value: nextRow ? countdownText(nextRow._start - now) : '—', label: '다음 일정까지', warn: false },
    { key: 'today', value: loaded ? String(todayCount) : '–', label: '오늘 일정', warn: false },
    { key: 'rem', value: reminderCount === null ? '–' : String(reminderCount), label: '대기 리마인더', warn: false },
    { key: 'conf', value: loaded ? String(conflictCount) : '–', label: '겹치는 일정', warn: conflictCount > 0 },
  ]

  // ── WEEKLY DENSITY: 오늘부터 7일의 실제 일정 개수 ──
  const week = weekBuckets(rows, now)
  const weekMax = Math.max(1, ...week.map((d) => d.count))

  const remList = reminders || []
  const remShown = remList.slice(0, REMINDER_ROWS)

  return (
    <section className="hud-panel">
      <div className="panel-header">
        <span>TODAY AT A GLANCE</span>
        <span className="tag">{todayKey.slice(5).replace('-', '/')}</span>
      </div>
      <div className="glance-grid">
        {glance.map((g) => (
          <div className="glance-tile" key={g.key}>
            <div className={'glance-val' + (g.warn ? ' warn' : '')}>{g.value}</div>
            <div className="glance-lbl">{g.label}</div>
          </div>
        ))}
      </div>

      <div className="sched-block">
        <div className="panel-header">
          <span>TODAY'S SCHEDULE</span>
          <span className="tag">{loaded ? count + ' ITEMS' : '- ITEMS'}</span>
        </div>
        <div className="sched-list">
          {!loaded && (
            <div className="sched-row">
              <span className="sched-title" style={{ color: '#4A6472' }}>일정을 불러오는 중...</span>
            </div>
          )}
          {loaded && rows.length === 0 && (
            <div className="sched-row">
              <span className="sched-title" style={{ color: '#4A6472' }}>등록된 일정이 없습니다</span>
            </div>
          )}
          {rows.slice(0, SCHEDULE_ROWS).map((s) => (
            <div className={'sched-row' + (s.isNext ? ' next' : '')} key={s.id ?? s.title + s.startTime}>
              <span className="sched-time">{pad(s._start.getHours())}:{pad(s._start.getMinutes())}</span>
              <div>
                <div className="sched-title">{s.title}</div>
                {s.hasConflict && <div className="sched-note">CONFLICT</div>}
              </div>
            </div>
          ))}
          {rows.length > SCHEDULE_ROWS && (
            <div className="mood-empty" style={{ marginTop: 6 }}>외 {rows.length - SCHEDULE_ROWS}건</div>
          )}
        </div>
        {conflictAlert && (
          <div className="alert-card">
            <div className="title">SCHEDULE CONFLICT</div>
            <div className="body">{conflictAlert}</div>
          </div>
        )}
      </div>

      <div className="chart-block">
        <div className="panel-header" style={{ marginBottom: 10 }}>
          <span>UPCOMING REMINDERS</span>
          <span className="tag">{reminderCount === null ? '-' : reminderCount + ' ITEMS'}</span>
        </div>
        <div className="rem-list">
          {reminders === null && <div className="mood-empty" style={{ marginTop: 0 }}>불러오는 중...</div>}
          {reminders !== null && remList.length === 0 && (
            <div className="mood-empty" style={{ marginTop: 0 }}>"내일 9시에 알려줘"라고 말하면 여기에 쌓여요</div>
          )}
          {remShown.map((r) => (
            <div className="rem-row" key={r.id}>
              <span className="rem-when">{reminderWhen(new Date(r.remindAt), now)}</span>
              <span className="rem-text">{r.content}</span>
            </div>
          ))}
          {remList.length > remShown.length && (
            <div className="mood-empty" style={{ marginTop: 6 }}>외 {remList.length - remShown.length}건</div>
          )}
        </div>
      </div>

      <div className="chart-block">
        <div className="panel-header" style={{ marginBottom: 12 }}>
          <span>WEEKLY DENSITY</span>
          <span className="tag">NEXT 7 DAYS</span>
        </div>
        <div className="bar-chart">
          {week.map((d) => {
            const pct = d.count === 0 ? 4 : Math.max(14, Math.round((d.count / weekMax) * 100))
            const cls = 'bar' + (d.conflict ? ' conflict' : d.today ? ' today' : '')
            return (
              <div
                key={d.key} className={cls} style={{ height: pct + '%' }}
                title={d.key.slice(5).replace('-', '/') + ' · 일정 ' + d.count + '건' + (d.conflict ? ' (겹침)' : '')}
              />
            )
          })}
        </div>
        <div className="bar-labels">
          {week.map((d) => <span key={d.key}>{d.label}</span>)}
        </div>
      </div>

      <div className="chart-block">
        <div className="panel-header" style={{ marginBottom: 10 }}>
          <span>MEMORY NOTES</span>
          <span className="tag">{notes.length} / 50</span>
        </div>
        <div className="note-list">
          {notes.length === 0 && <div className="mood-empty" style={{ marginTop: 0 }}>"~기억해 줘"라고 말하거나 아래에 직접 추가하세요</div>}
          {notes.map((n) => (
            <div className="note-row" key={n.id}>
              <span className="note-text">{n.content}</span>
              <button className="note-del" type="button" title="이 노트 삭제" onClick={() => deleteNote(n.id)}>✕</button>
            </div>
          ))}
        </div>
        <form className="note-add" onSubmit={(e) => { e.preventDefault(); submitNote() }}>
          <input
            type="text" value={noteText} maxLength={200} placeholder="기억할 내용…"
            onChange={(e) => setNoteText(e.target.value)}
          />
          <button type="submit">추가</button>
        </form>
        <div className="note-foot">
          <span>비밀번호·카드번호는 저장되지 않아요</span>
          {notes.length > 0 && <button type="button" onClick={() => { if (window.confirm('노트를 모두 삭제할까요?')) clearNotes() }}>전체 삭제</button>}
        </div>
      </div>
    </section>
  )
}
