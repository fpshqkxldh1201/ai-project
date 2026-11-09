import { useEffect, useRef, useState } from 'react'
import { useLumi } from '../context/LumiContext.jsx'
import { createNeuronScene } from '../three/neuronScene.js'
import ResultStage from './ResultStage.jsx'

// 나침반 눈금 36개를 SVG 라인으로 생성
function CompassTicks() {
  const cx = 100, cy = 100, r1 = 97, r2 = 101
  const lines = []
  for (let i = 0; i < 36; i++) {
    const angle = (i * 10) * Math.PI / 180
    lines.push(
      <line
        key={i}
        x1={(cx + r1 * Math.cos(angle)).toFixed(2)}
        y1={(cy + r1 * Math.sin(angle)).toFixed(2)}
        x2={(cx + r2 * Math.cos(angle)).toFixed(2)}
        y2={(cy + r2 * Math.sin(angle)).toFixed(2)}
      />
    )
  }
  return <g stroke="rgba(63,224,255,0.25)" strokeWidth="0.5">{lines}</g>
}

export default function CenterCore() {
  const { activeState } = useLumi()
  const slotRef = useRef(null)
  const activeRef = useRef(activeState.active)
  const [hasCards, setHasCards] = useState(false)

  useEffect(() => {
    activeRef.current = activeState.active
  }, [activeState.active])

  useEffect(() => {
    const controller = createNeuronScene(slotRef.current, { isActive: () => activeRef.current })
    return () => controller.dispose()
    // 씬은 마운트 시 한 번만 생성 — 리스닝 상태 변화는 activeRef로 매 프레임 반영한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const listening = activeState.active

  return (
    <section className={'center-hud' + (hasCards ? ' has-cards' : '')}>
      <div className={'core-stage' + (listening ? ' listening' : '')}>
        <svg className={'core-svg overlay-svg' + (listening ? ' listening' : '')} viewBox="0 0 200 200">
          <CompassTicks />
          <circle className={'pulse-ring' + (listening ? ' listening' : '')} cx="100" cy="100" r="40" />
          <circle className={'pulse-ring two' + (listening ? ' listening' : '')} cx="100" cy="100" r="40" />
        </svg>
        <div className="canvas-slot" ref={slotRef}></div>
      </div>
      <ResultStage onOpenChange={setHasCards} />
      <div className={'center-state' + (listening ? ' listening' : '')}>{activeState.label}</div>
      <div className="center-caption">NEURAL ACTIVITY — 실시간 처리 시각화</div>
    </section>
  )
}
