import { Fragment, useEffect, useState } from 'react'
import { pad } from '../hooks/useClock.js'
import { useLumi } from '../context/LumiContext.jsx'
import { countdownText, reminderWhen, weekBuckets } from '../utils/dashboardTime.js'

const SCHEDULE_COLLAPSED_ROWS = 3 // 접힌 상태에서 보여줄 일정 개수(나머지는 펼치기 버튼)
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const dayKeyOf = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
function dayLabel(d, now) {
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  const base = pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' (' + WEEKDAYS[d.getDay()] + ')'
  if (dayKeyOf(d) === dayKeyOf(now)) return base + ' · 오늘'
  if (dayKeyOf(d) === dayKeyOf(tomorrow)) return base + ' · 내일'
  return base
}
const REMINDER_ROWS = 4 // 패널에 보여줄 개수(나머지는 '외 N건')

export default function RightPanel() {
  const { schedules, reminders, notes, addNote, deleteNote, clearNotes, updateSchedule, deleteSchedule, deleteScheduleSeries } = useLumi()
  const [noteText, setNoteText] = useState('')
  // 남은 시간 표시가 가만히 있어도 줄어들도록 30초마다 현재 시각을 갱신
  const [now, setNow] = useState(() => new Date())
  const [schedOpen, setSchedOpen] = useState(false)
  const [editId, setEditId] = useState(null)       // 수정 중인 일정 id
  const [confirmId, setConfirmId] = useState(null) // 삭제 확인 중인 일정 id
  const [editForm, setEditForm] = useState({ title: '', start: '', end: '' })
  const [editError, setEditError] = useState('')
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30000)
    return () => clearInterval(id)
  }, [])

  const toLocalInput = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes())

  function startEdit(s) {
    setEditId(s.id)
    setConfirmId(null)
    setEditError('')
    setEditForm({ title: s.title, start: (s.startTime || '').slice(0, 16), end: (s.endTime || '').slice(0, 16), origStart: (s.startTime || '').slice(0, 16), origEnd: (s.endTime || '').slice(0, 16) })
  }

  async function saveEdit() {
    const { title, start, origStart, origEnd } = editForm
    let end = editForm.end
    if (!title.trim()) return setEditError('제목을 입력하세요.')
    if (!start) return setEditError('시작 시각을 입력하세요.')
    if (end) {
      const st = new Date(start)
      if (end === origEnd && start !== origStart && origStart) {
        const len = new Date(origEnd) - new Date(origStart) // 시작만 옮겼으면 원래 길이를 유지
        end = toLocalInput(new Date(st.getTime() + (len > 0 ? len : 3600000)))
      } else if (new Date(end) <= st) {
        end = '' // 서버가 시작+1시간으로 정한다
      }
    }
    const err = await updateSchedule(editId, {
      title: title.trim(),
      startTime: start.length === 16 ? start + ':00' : start,
      endTime: end ? (end.length === 16 ? end + ':00' : end) : null,
    })
    if (err) setEditError(err)
    else setEditId(null)
  }

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
          <span>SCHEDULE</span>
          <span className="tag">
            {loaded
              ? (rows.length === 0 ? '0 ITEMS' : '오늘 ' + rows.filter((s) => dayKeyOf(s._start) === dayKeyOf(now)).length + ' · 전체 ' + count)
              : '- ITEMS'}
          </span>
        </div>
        <div className={'sched-list' + (schedOpen ? ' expanded' : '')}>
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
          {(schedOpen ? rows : rows.slice(0, SCHEDULE_COLLAPSED_ROWS)).map((s, i, arr) => {
            const showDay = schedOpen && (i === 0 || dayKeyOf(arr[i - 1]._start) !== dayKeyOf(s._start))
            const showWhen = !schedOpen && dayKeyOf(s._start) !== dayKeyOf(now)
            const sameTitle = rows.filter((x) => x.title === s.title).length
            return (
              <Fragment key={s.id ?? s.title + s.startTime}>
                {showDay && (
                  <div className={'sched-day' + (dayKeyOf(s._start) === dayKeyOf(now) ? ' today' : '')}>{dayLabel(s._start, now)}</div>
                )}
                {editId === s.id ? (
                  <div className="sched-row">
                    <div className="sched-edit">
                      <label>TITLE<input type="text" maxLength={100} value={editForm.title} onChange={(e) => setEditForm({ ...editForm, title: e.target.value })} /></label>
                      <label>START<input type="datetime-local" value={editForm.start} onChange={(e) => setEditForm({ ...editForm, start: e.target.value })} /></label>
                      <label>END (선택)<input type="datetime-local" value={editForm.end} onChange={(e) => setEditForm({ ...editForm, end: e.target.value })} /></label>
                      {editError && <div className="err">{editError}</div>}
                      <div className="btns">
                        <button type="button" className="sched-toggle" style={{ marginTop: 0 }} onClick={saveEdit}>저장</button>
                        <button type="button" className="sched-toggle" style={{ marginTop: 0 }} onClick={() => { setEditId(null); setEditError('') }}>취소</button>
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className={'sched-row' + (s.isNext ? ' next' : '')}>
                    <span className="sched-time">{pad(s._start.getHours())}:{pad(s._start.getMinutes())}</span>
                    <div style={{ flex: 1 }}>
                      <div className="sched-title">{s.title}</div>
                      {showWhen && <div className="sched-when">{dayLabel(s._start, now)}</div>}
                      {s.hasConflict && <div className="sched-note">CONFLICT</div>}
                      {confirmId === s.id && (
                        <div className="sched-confirm">
                          <span>삭제할까요?</span>
                          <button type="button" onClick={() => { setConfirmId(null); deleteSchedule(s.id) }}>이 일정만</button>
                          {sameTitle > 1 && (
                            <button type="button" onClick={() => { setConfirmId(null); deleteScheduleSeries(s.title) }}>'{s.title}' {sameTitle}건 모두</button>
                          )}
                          <button type="button" className="ghost" onClick={() => setConfirmId(null)}>취소</button>
                        </div>
                      )}
                    </div>
                    <div className="sched-actions">
                      <button type="button" className="sched-act" title="수정" aria-label="일정 수정" onClick={() => startEdit(s)}>✎</button>
                      <button type="button" className="sched-act del" title="삭제" aria-label="일정 삭제" onClick={() => { setConfirmId(s.id); setEditId(null) }}>✕</button>
                    </div>
                  </div>
                )}
              </Fragment>
            )
          })}
        </div>
        {rows.length > SCHEDULE_COLLAPSED_ROWS && (
          <button type="button" className="sched-toggle" aria-expanded={schedOpen} onClick={() => setSchedOpen((v) => !v)}>
            {schedOpen ? '접기 ▴' : '전체 ' + rows.length + '건 펼치기 ▾'}
          </button>
        )}
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
