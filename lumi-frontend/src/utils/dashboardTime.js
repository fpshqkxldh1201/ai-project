// 우측 패널(TODAY AT A GLANCE / UPCOMING REMINDERS / WEEKLY DENSITY)용 시간 계산.
// 서버의 시각은 타임존 없는 로컬 ISO 문자열이라 new Date()로 읽으면 브라우저 로컬 시각이 된다.
// (정적 index.html에도 같은 함수가 들어 있다 — 바꾸면 양쪽을 같이 바꿀 것)

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const pad2 = (n) => String(n).padStart(2, '0')

export function dayKey(d) {
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
}

/** 남은 시간을 짧은 한국어로: '곧' / '42분' / '1시간 20분' / '2일 3시간' */
export function countdownText(ms) {
  const m = Math.round(Math.max(0, ms) / 60000)
  if (m < 1) return '곧'
  if (m < 60) return m + '분'
  const h = Math.floor(m / 60)
  const mm = m % 60
  if (h < 24) return mm ? h + '시간 ' + mm + '분' : h + '시간'
  const d = Math.floor(h / 24)
  const hh = h % 24
  return hh ? d + '일 ' + hh + '시간' : d + '일'
}

/** 오늘부터 7일: [{ key, label, count, conflict, today }] — rows는 { _start, hasConflict } 목록 */
export function weekBuckets(rows, now) {
  const days = []
  for (let i = 0; i < 7; i++) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i)
    days.push({ key: dayKey(d), label: WEEKDAYS[d.getDay()], count: 0, conflict: false, today: i === 0, date: d })
  }
  for (const r of rows) {
    const b = days.find((x) => x.key === dayKey(r._start))
    if (!b) continue
    b.count += 1
    if (r.hasConflict) b.conflict = true
  }
  return days
}

/** 리마인더 시각 표시: '오늘 15:30' / '내일 09:00' / '10/14 09:00' */
export function reminderWhen(d, now) {
  const hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes())
  const todayKey = dayKey(now)
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  const k = dayKey(d)
  if (k === todayKey) return '오늘 ' + hm
  if (k === dayKey(tomorrow)) return '내일 ' + hm
  return pad2(d.getMonth() + 1) + '/' + pad2(d.getDate()) + ' ' + hm
}
