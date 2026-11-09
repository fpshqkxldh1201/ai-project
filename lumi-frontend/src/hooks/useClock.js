import { useEffect, useState } from 'react'

export function pad(n) {
  return n.toString().padStart(2, '0')
}

export function formatClock(date) {
  return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
}

export default function useClock() {
  const [text, setText] = useState(() => formatClock(new Date()))
  useEffect(() => {
    const id = setInterval(() => setText(formatClock(new Date())), 1000)
    return () => clearInterval(id)
  }, [])
  return text
}
