import { createContext, useCallback, useContext, useRef, useState } from 'react'

const ToastContext = createContext(null)

let idCounter = 0

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]) // { id, key|null, status, title, detail, leaving }
  const dismissTimers = useRef({})

  const removeToast = useCallback((id) => {
    setToasts((prev) => prev.map((t) => (t.id === id ? { ...t, leaving: true } : t)))
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 250)
  }, [])

  // key로 슬롯을 하나만 유지한다: 실패하면 그 슬롯을 계속 띄워두고(안 사라짐),
  // 이후 성공하면 같은 슬롯을 성공 메시지로 바꾼 뒤 잠시 후 스스로 사라진다.
  // 처음부터 성공(이전에 에러 표시가 없었던 경우)이면 굳이 토스트를 띄우지 않는다.
  const upsertToast = useCallback((key, status, title, detail) => {
    setToasts((prev) => {
      const existing = prev.find((t) => t.key === key)
      if (status === 'success' && !existing) return prev

      if (dismissTimers.current[key]) {
        clearTimeout(dismissTimers.current[key])
        delete dismissTimers.current[key]
      }

      let next
      if (existing) {
        next = prev.map((t) => (t.key === key ? { ...t, status, title, detail, leaving: false } : t))
      } else {
        next = [...prev, { id: ++idCounter, key, status, title, detail, leaving: false }]
      }

      if (status === 'success') {
        dismissTimers.current[key] = setTimeout(() => {
          const toast = next.find((t) => t.key === key)
          if (toast) removeToast(toast.id)
        }, 3000)
      }
      // 실패 상태는 타이머를 걸지 않는다 — 다음 재조회가 성공할 때까지 화면에 고정.
      return next
    })
  }, [removeToast])

  // upsertToast와 달리 슬롯을 유지하지 않는 1회성 알림 — STT처럼 폴링이 아니라
  // 사용자 행동으로 한 번 발생하는 이벤트에 사용한다. 일정 시간 후 항상 사라진다.
  const showToast = useCallback((status, title, detail, durationMs) => {
    const id = ++idCounter
    setToasts((prev) => [...prev, { id, key: null, status, title, detail, leaving: false }])
    setTimeout(() => removeToast(id), durationMs || 4000)
  }, [removeToast])

  return (
    <ToastContext.Provider value={{ toasts, upsertToast, showToast }}>
      {children}
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within a ToastProvider')
  return ctx
}
