import * as THREE from 'three'

// Lumi 코어 3D 비주얼 — Three.js 뉴런 플렉서스(발광 노드-네트워크, 호흡 펄스, 신호 전파).
// 원본 static/js/neuron-scene.js의 로직을 그대로 포팅하되, `listening` 전역 변수 대신
// isActive() 콜백을 매 프레임 읽어 색을 바꾼다 (React 상태 변경 때마다 씬을 다시 만들지 않기 위함).
export function createNeuronScene(container, { isActive } = { isActive: () => false }) {
  const STAGE_SIZE = Math.max(200, Math.min(570, container.clientWidth || 570))
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(45, 1, 1, 1000)
  camera.position.z = 460
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
  renderer.setSize(STAGE_SIZE, STAGE_SIZE)
  renderer.setPixelRatio(window.devicePixelRatio || 1)
  container.appendChild(renderer.domElement)

  const handleResize = () => {
    const size = Math.max(200, Math.min(570, container.clientWidth || 570))
    renderer.setSize(size, size)
  }
  window.addEventListener('resize', handleResize)

  const NODE_COUNT = 500
  const SPHERE_R = 112
  const baseColor = new THREE.Color('#3FE0FF')
  const positions = new Float32Array(NODE_COUNT * 3)
  const nodeColors = new Float32Array(NODE_COUNT * 3)
  const nodeVecs = []
  const nodePhase = new Float32Array(NODE_COUNT)
  const nodeClusterIndex = new Int16Array(NODE_COUNT)
  const PULSE_FREQ = 3.1, PULSE_AMP = 14 // 전체 노드가 동시에 커졌다 작아지는 펄스 (호흡과 같은 주파수로 동기화)
  const NOISE_FREQ = 1.2, NOISE_AMP = 5 // 노드마다 제각각인 무작위 위상의 노이즈

  // 몇 개의 '클러스터 중심'을 구 표면에 흩뿌리고, 그 주변에 노드를 뭉치게 배치해
  // 완전히 균일한 격자가 아니라 밀도가 들쭉날쭉한 유기적인 형태를 만든다
  const CLUSTER_COUNT = 16
  const clusters = []
  for (let c = 0; c < CLUSTER_COUNT; c++) {
    const u = Math.random(), v = Math.random()
    const theta = u * 2 * Math.PI, phi = Math.acos(2 * v - 1)
    clusters.push(new THREE.Vector3(Math.sin(phi) * Math.cos(theta), Math.sin(phi) * Math.sin(theta), Math.cos(phi)))
  }

  for (let i = 0; i < NODE_COUNT; i++) {
    let x, y, z
    let clusterIdx = -1
    if (Math.random() < 0.22) {
      // 클러스터 중심 근처에 가우시안 느낌으로 뭉치게 배치
      clusterIdx = Math.floor(Math.random() * CLUSTER_COUNT)
      const center = clusters[clusterIdx]
      const spread = 0.32
      const jitter = new THREE.Vector3(
        (Math.random() + Math.random() + Math.random() - 1.5) * spread,
        (Math.random() + Math.random() + Math.random() - 1.5) * spread,
        (Math.random() + Math.random() + Math.random() - 1.5) * spread
      )
      const dir = center.clone().add(jitter).normalize()
      const r = SPHERE_R * (0.88 + Math.random() * 0.2)
      x = dir.x * r; y = dir.y * r; z = dir.z * r
    } else {
      // 나머지는 배경 텍스처용으로 고르게 분포
      const u = Math.random(), v = Math.random()
      const theta = u * 2 * Math.PI, phi = Math.acos(2 * v - 1)
      const r = SPHERE_R * (0.86 + Math.random() * 0.22)
      x = r * Math.sin(phi) * Math.cos(theta)
      y = r * Math.sin(phi) * Math.sin(theta)
      z = r * Math.cos(phi)
    }
    positions[i * 3] = x; positions[i * 3 + 1] = y; positions[i * 3 + 2] = z
    nodeColors[i * 3] = baseColor.r; nodeColors[i * 3 + 1] = baseColor.g; nodeColors[i * 3 + 2] = baseColor.b
    nodeVecs.push(new THREE.Vector3(x, y, z))
    nodePhase[i] = Math.random() * Math.PI * 2
    nodeClusterIndex[i] = clusterIdx
  }
  const currentPos = nodeVecs.map((v) => v.clone())

  // 클러스터별로 '한 번씩 무작위로 확 뭉쳤다가 원래 거리로 풀리는' 상태를 관리
  const clusterContract = new Float32Array(CLUSTER_COUNT)
  const clusterState = Array.from({ length: CLUSTER_COUNT }, () => ({ active: false, t: 0 }))
  const CONTRACT_DURATION = 2.0 // 한 번 뭉쳤다 풀리는 데 걸리는 시간(초)
  const CONTRACT_STRENGTH = 0.4 // 얼마나 가까워지는지 (0~1) — 너무 심하게 뭉쳐 보이지 않도록 완화
  const MAX_CONCURRENT_CONTRACT = 1 // 동시에 뭉칠 수 있는 클러스터 수 제한 (풀렸을 때 확실히 균일해 보이게)
  const contractInterval = setInterval(() => {
    const activeCount = clusterState.filter((s) => s.active).length
    if (activeCount >= MAX_CONCURRENT_CONTRACT) return
    const idle = clusterState.map((s, i) => ({ s, i })).filter(({ s }) => !s.active)
    if (!idle.length) return
    if (Math.random() < 0.35) {
      const pick = idle[Math.floor(Math.random() * idle.length)].s
      pick.active = true; pick.t = 0
    }
  }, 300)

  const pointGeom = new THREE.BufferGeometry()
  pointGeom.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  pointGeom.setAttribute('color', new THREE.BufferAttribute(nodeColors, 3))
  const pointMat = new THREE.PointsMaterial({ size: 3.3, vertexColors: true, transparent: true, opacity: 0.95, sizeAttenuation: true, blending: THREE.AdditiveBlending })
  const points = new THREE.Points(pointGeom, pointMat)

  // 연결: 매 프레임 노드 사이의 실제 거리를 다시 계산해서, 가까우면 선을 긋고
  // 멀어지면 선을 빼버리는 방식 — 그래서 펄스·노이즈로 움직일 때마다 선이 실제로 생겼다 사라짐
  const BASE_MAX_DIST = 39.5 // 항상 유지되는 기본 연결 거리 (약 4100대 선을 유지)
  const EXTRA_MAX_DIST = 18 // 구체가 팽창(성장)할 때 추가로 늘어나는 연결 거리
  const MAX_EDGES = 20000 // 한 프레임에 그릴 수 있는 선의 최대 개수(성능 상한)
  const linePositions = new Float32Array(MAX_EDGES * 6)
  const lineColorsArr = new Float32Array(MAX_EDGES * 6)
  const lineGeom = new THREE.BufferGeometry()
  lineGeom.setAttribute('position', new THREE.BufferAttribute(linePositions, 3))
  lineGeom.setAttribute('color', new THREE.BufferAttribute(lineColorsArr, 3))
  lineGeom.setDrawRange(0, 0)
  const lineMat = new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.75, blending: THREE.AdditiveBlending })
  const lines = new THREE.LineSegments(lineGeom, lineMat)

  // 이번 프레임에 실제로 연결된 쌍의 목록 (신호 발화·펄스 이동이 여기서 이웃을 찾음)
  let currentEdges = []

  // 신호가 노드 사이를 실제로 이동하는 펄스 (작은 발광점이 엣지를 타고 이동)
  const PULSE_COUNT = 46
  const pulsePositions = new Float32Array(PULSE_COUNT * 3).fill(9999)
  const pulseColors = new Float32Array(PULSE_COUNT * 3)
  const pulseGeom = new THREE.BufferGeometry()
  pulseGeom.setAttribute('position', new THREE.BufferAttribute(pulsePositions, 3))
  pulseGeom.setAttribute('color', new THREE.BufferAttribute(pulseColors, 3))
  const pulseMat = new THREE.PointsMaterial({ size: 4.6, vertexColors: true, transparent: true, opacity: 1, sizeAttenuation: true, blending: THREE.AdditiveBlending })
  const pulsePoints = new THREE.Points(pulseGeom, pulseMat)
  const pulseData = Array.from({ length: PULSE_COUNT }, () => ({ active: false }))

  const group = new THREE.Group()
  group.add(points); group.add(lines); group.add(pulsePoints)
  scene.add(group)

  // 코어를 감싸는 3D 링 두 개 — 실제로 기울여서 원근감이 생기게 하고, 서로 다른 방향/속도로 회전
  function makeRingLine(radius, segments, color, opacity) {
    const pts = []
    for (let i = 0; i <= segments; i++) {
      const a = (i / segments) * Math.PI * 2
      pts.push(new THREE.Vector3(radius * Math.cos(a), radius * Math.sin(a), 0))
    }
    return new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), new THREE.LineBasicMaterial({ color, transparent: true, opacity }))
  }
  function makeRingParticles(radius, count, color, size) {
    const pos = new Float32Array(count * 3)
    for (let i = 0; i < count; i++) {
      const a = (i / count) * Math.PI * 2
      pos[i * 3] = radius * Math.cos(a); pos[i * 3 + 1] = radius * Math.sin(a); pos[i * 3 + 2] = 0
    }
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    return new THREE.Points(g, new THREE.PointsMaterial({ color, size, transparent: true, opacity: 0.85, sizeAttenuation: true }))
  }

  const ring1 = new THREE.Group()
  ring1.add(makeRingLine(76, 110, 0x3fe0ff, 0.5))
  ring1.add(makeRingParticles(76, 16, 0x3fe0ff, 3.2))
  ring1.rotation.x = 1.3
  ring1.rotation.y = 0.2
  scene.add(ring1)

  const ring2 = new THREE.Group()
  ring2.add(makeRingLine(92, 110, 0xffa94d, 0.42))
  ring2.add(makeRingParticles(92, 12, 0xffa94d, 2.6))
  ring2.rotation.x = 1.0
  ring2.rotation.z = 0.45
  scene.add(ring2)

  const nodeBoost = new Float32Array(NODE_COUNT)
  const pointColorAttr = pointGeom.getAttribute('color')
  const pulsePosAttr = pulseGeom.getAttribute('position')
  const pulseColorAttr = pulseGeom.getAttribute('color')
  const cyan = new THREE.Color('#3FE0FF')
  const amber = new THREE.Color('#FFA94D')

  function fireNeurons() {
    for (let k = 0; k < 3; k++) {
      const i = Math.floor(Math.random() * NODE_COUNT)
      nodeBoost[i] = 1
      currentEdges.forEach(([a, b]) => {
        if (a !== i && b !== i) return
        const to = a === i ? b : a
        setTimeout(() => { nodeBoost[to] = Math.max(nodeBoost[to], 0.9) }, 80 + Math.random() * 100)
      })
    }
  }
  const fireInterval = setInterval(fireNeurons, 220)

  // 신호가 실제로 노드 사이를 이동하는 펄스를 주기적으로 발사 (현재 연결된 쌍 중에서 고름)
  function spawnPulse() {
    if (!currentEdges.length) return
    const slot = pulseData.findIndex((p) => !p.active)
    if (slot === -1) return
    const [from, to] = currentEdges[Math.floor(Math.random() * currentEdges.length)]
    pulseData[slot] = { active: true, from, to, t: 0, speed: 0.018 + Math.random() * 0.022 }
  }
  const spawnInterval = setInterval(spawnPulse, 70)

  let clockTime = 0
  let rafId = null
  let disposed = false

  function renderLoop() {
    if (disposed) return
    rafId = requestAnimationFrame(renderLoop)
    clockTime += 0.016
    group.rotation.y += 0.005
    group.rotation.x += 0.002
    ring1.rotation.y += 0.010
    ring2.rotation.y -= 0.007

    // 구체와 링이 함께 커졌다 작아지는 브리딩 펄스 — 커질 땐 진폭 그대로, 작아질 땐 절반만
    const breathRaw = Math.sin(clockTime * 1.5)
    const breathAmp = breathRaw >= 0 ? 0.25 : 0.125
    const breath = 1 + breathRaw * breathAmp
    group.scale.set(breath, breath, breath)
    ring1.scale.set(breath, breath, breath)
    ring2.scale.set(breath, breath, breath)

    const active = isActive() ? amber : cyan

    // 클러스터별 '무작위로 확 뭉쳤다가 원래 거리로 풀리는' 상태 갱신
    for (let c = 0; c < CLUSTER_COUNT; c++) {
      const s = clusterState[c]
      if (!s.active) { clusterContract[c] = 0; continue }
      s.t += 0.016
      if (s.t >= CONTRACT_DURATION) { s.active = false; s.t = 0; clusterContract[c] = 0; continue }
      clusterContract[c] = Math.sin((s.t / CONTRACT_DURATION) * Math.PI) * CONTRACT_STRENGTH
    }

    // 전체 노드가 함께 커졌다 작아지는 펄스(위 브리딩과 동일한 박자·비대칭) + 노드별 무작위 노이즈
    const pulseAmp = breathRaw >= 0 ? PULSE_AMP : PULSE_AMP * 0.5
    const pulse = breathRaw * pulseAmp
    for (let i = 0; i < NODE_COUNT; i++) {
      const base = nodeVecs[i]
      const noise = Math.sin(clockTime * NOISE_FREQ + nodePhase[i]) * NOISE_AMP
      const wobble = pulse + noise
      const inv = 1 / base.length()
      let cx = base.x + base.x * inv * wobble
      let cy = base.y + base.y * inv * wobble
      let cz = base.z + base.z * inv * wobble

      // 자기 클러스터가 지금 뭉치는 중이면, 그 클러스터의 고정된 한 지점으로 끌어당김
      // (각자 다른 반지름이 아니라 같은 지점으로 모이게 해서 실제로 한 덩어리로 뭉치는 게 보이게 함)
      const ci = nodeClusterIndex[i]
      if (ci >= 0 && clusterContract[ci] > 0) {
        const amt = clusterContract[ci]
        const dir = clusters[ci]
        const anchorX = dir.x * SPHERE_R, anchorY = dir.y * SPHERE_R, anchorZ = dir.z * SPHERE_R
        cx += (anchorX - cx) * amt
        cy += (anchorY - cy) * amt
        cz += (anchorZ - cz) * amt
      }

      currentPos[i].set(cx, cy, cz)
      pointGeom.attributes.position.setXYZ(i, cx, cy, cz)

      nodeBoost[i] *= 0.9
      if (ci >= 0) nodeBoost[i] = Math.max(nodeBoost[i], clusterContract[ci])
      const t = nodeBoost[i]
      pointColorAttr.setXYZ(
        i,
        baseColor.r + (active.r - baseColor.r) * t,
        baseColor.g + (active.g - baseColor.g) * t,
        baseColor.b + (active.b - baseColor.b) * t
      )
    }
    pointGeom.attributes.position.needsUpdate = true
    pointColorAttr.needsUpdate = true

    // 매 프레임 노드 사이 실제 거리를 다시 재서, 가까운 쌍만 골라 선을 그린다.
    // 기준 거리 자체를 펄스 단계에 맞춰 늘렸다 줄였다 해서, 구체가 팽창(성장)할 때
    // 기본 연결에 더해 선이 추가로 생기고, 수축할 때는 기본 연결만 남도록 한다.
    const pulseFactor = (breathRaw + 1) / 2 // 0(수축)~1(팽창)
    const dynamicMaxDist = BASE_MAX_DIST + EXTRA_MAX_DIST * pulseFactor
    const maxDistSq = dynamicMaxDist * dynamicMaxDist
    currentEdges = []
    let edgeCount = 0
    for (let i = 0; i < NODE_COUNT && edgeCount < MAX_EDGES; i++) {
      const pa = currentPos[i]
      for (let j = i + 1; j < NODE_COUNT; j++) {
        const pb = currentPos[j]
        const dx = pa.x - pb.x, dy = pa.y - pb.y, dz = pa.z - pb.z
        const distSq = dx * dx + dy * dy + dz * dz
        if (distSq > maxDistSq) continue

        const o = edgeCount * 6
        linePositions[o] = pa.x; linePositions[o + 1] = pa.y; linePositions[o + 2] = pa.z
        linePositions[o + 3] = pb.x; linePositions[o + 4] = pb.y; linePositions[o + 5] = pb.z

        const t = Math.max(nodeBoost[i], nodeBoost[j])
        const r = baseColor.r + (active.r - baseColor.r) * t
        const g = baseColor.g + (active.g - baseColor.g) * t
        const bch = baseColor.b + (active.b - baseColor.b) * t
        lineColorsArr[o] = r; lineColorsArr[o + 1] = g; lineColorsArr[o + 2] = bch
        lineColorsArr[o + 3] = r; lineColorsArr[o + 4] = g; lineColorsArr[o + 5] = bch

        currentEdges.push([i, j])
        edgeCount++
        if (edgeCount >= MAX_EDGES) break
      }
    }
    lineGeom.setDrawRange(0, edgeCount * 2)
    lineGeom.attributes.position.needsUpdate = true
    lineGeom.attributes.color.needsUpdate = true

    // 노드 사이를 이동 중인 신호 펄스 갱신
    for (let p = 0; p < PULSE_COUNT; p++) {
      const pd = pulseData[p]
      if (!pd.active) { pulsePosAttr.setXYZ(p, 9999, 9999, 9999); continue }
      pd.t += pd.speed
      if (pd.t >= 1) {
        nodeBoost[pd.to] = 1
        pd.active = false
        pulsePosAttr.setXYZ(p, 9999, 9999, 9999)
        continue
      }
      const a = currentPos[pd.from], b = currentPos[pd.to]
      pulsePosAttr.setXYZ(p, a.x + (b.x - a.x) * pd.t, a.y + (b.y - a.y) * pd.t, a.z + (b.z - a.z) * pd.t)
      pulseColorAttr.setXYZ(p, active.r, active.g, active.b)
    }
    pulsePosAttr.needsUpdate = true
    pulseColorAttr.needsUpdate = true

    renderer.render(scene, camera)
  }
  renderLoop()

  return {
    dispose() {
      disposed = true
      if (rafId != null) cancelAnimationFrame(rafId)
      clearInterval(contractInterval)
      clearInterval(fireInterval)
      clearInterval(spawnInterval)
      window.removeEventListener('resize', handleResize)
      renderer.dispose()
      pointGeom.dispose(); pointMat.dispose()
      lineGeom.dispose(); lineMat.dispose()
      pulseGeom.dispose(); pulseMat.dispose()
      if (renderer.domElement.parentNode === container) container.removeChild(renderer.domElement)
    },
  }
}
