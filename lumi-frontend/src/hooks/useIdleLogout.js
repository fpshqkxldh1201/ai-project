import { useEffect } from 'react'

// 자리 비움 자동 로그아웃 (정보보안개론 3장: 세션 하이재킹 대응 — 열려 있는 로그인 상태를 오래 두지 않는다).
// 사용자 조작(클릭·키보드·터치·스크롤)이나 사용자가 일으킨 서버 요청(대화 등)이 IDLE_MS 동안 없으면 로그아웃한다.
// 30초마다 자동으로 가는 알림 확인(/api/notifications/pending)은 '사용'으로 치지 않는다 — 서버(SessionGuardFilter)와 같은 기준.
const IDLE_MS = 15 * 60 * 1000
const WARN_BEFORE_MS = 60 * 1000
const CHECK_MS = 10 * 1000
const PASSIVE_URL = '/api/notifications/pending'

export default function useIdleLogout({ onWarn, onTimeout }) {
  useEffect(() => {
    let last = Date.now()
    let warned = false

    function markActive() {
      last = Date.now()
      warned = false
    }

    const events = ['pointerdown', 'keydown', 'touchstart', 'wheel']
    events.forEach((e) => window.addEventListener(e, markActive, { passive: true }))

    // 음성 대화처럼 클릭 없이 서버 요청만 오가는 사용도 '활동'으로 인정한다.
    const originalFetch = window.fetch
    window.fetch = function (input, init) {
      const url = typeof input === 'string' ? input : input?.url || ''
      if (!url.startsWith(PASSIVE_URL)) markActive()
      return originalFetch.call(this, input, init)
    }

    const timer = setInterval(() => {
      const idle = Date.now() - last
      if (idle >= IDLE_MS) {
        clearInterval(timer)
        onTimeout()
      } else if (idle >= IDLE_MS - WARN_BEFORE_MS && !warned) {
        warned = true
        onWarn()
      }
    }, CHECK_MS)

    return () => {
      clearInterval(timer)
      events.forEach((e) => window.removeEventListener(e, markActive))
      window.fetch = originalFetch
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
