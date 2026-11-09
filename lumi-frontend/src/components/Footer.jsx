import { useLumi } from '../context/LumiContext.jsx'

export default function Footer() {
  const { activeState, toggleMic } = useLumi()
  const listening = activeState.active

  return (
    <footer>
      <div className="voice-monitor">
        <div className="row">
          <span>LUMI VOICE MATRIX</span>
          <span className={'state' + (listening ? ' listening' : '')}>{activeState.label}</span>
        </div>
        <span className="hint">클릭하여 음성 입력 전환</span>
      </div>
      <div className={'eq-wave' + (listening ? ' listening' : '')} onClick={toggleMic}>
        <div className="eq-bar"></div><div className="eq-bar"></div><div className="eq-bar"></div><div className="eq-bar"></div><div className="eq-bar"></div>
      </div>
    </footer>
  )
}
