import useClock from '../hooks/useClock.js'

export default function Header() {
  const clockText = useClock()
  return (
    <header>
      <div className="brand-container">
        <div className="brand-logo"></div>
        <div className="brand-title">LUMI</div>
      </div>
      <div className="status-bar">
        <div className="status-item"><div className="badge-live"></div>GEMINI · CONNECTED</div>
        <div className="status-item">TTS · READY</div>
        <div className="status-item time">{clockText}</div>
      </div>
    </header>
  )
}
