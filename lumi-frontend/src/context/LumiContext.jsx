import { serverStt } from '../lib/serverStt'
import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useToast } from './ToastContext.jsx'
import { prepareImage } from '../utils/imageUpload.js'

const LumiContext = createContext(null)

// CSRF(쿠키 기반 더블 서브밋): AuthContext.jsx와 같은 패턴. 백엔드가 심어주는
// XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 되돌려보내야 POST가 통과된다.
function getCsrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

const EMOTION_LABEL_KO = {
  happy: '행복', energetic: '활기참', calm: '차분함', sad: '슬픔',
  angry: '화남', dreamy: '몽환적', romantic: '설렘', melancholy: '우울',
}

function sttLangCode(code) {
  switch (code) {
    case 'en': return 'en-US'
    case 'zh': return 'zh-CN'
    case 'ja': return 'ja-JP'
    case 'es': return 'es-ES'
    default: return 'ko-KR'
  }
}

// Gemini 호출(특히 일정/리마인더 조회나 웹 검색처럼 함수 호출이 섞이는 경우)이 오래
// 걸릴 때, 아무 말 없이 멈춰 있으면 멈춘 것처럼 느껴진다. 짧게 기다리다 응답이 안 오면
// "확인해볼게요" 같은 짧은 대사를 먼저 들려주고, 그래도 한참 더 걸리면 조금 더 걸린다는
// 안내를 한 번 더 들려줘서 빈 시간을 채운다.
const FILLER_PHRASES_SHORT = {
  ko: ['확인해볼게요.', '잠시만요, 확인하고 있어요.', '네, 바로 찾아볼게요.', '조금만 기다려 주세요, 확인 중이에요.'],
  en: ['Let me check that.', 'One moment, looking into it.', 'Sure, give me a second.', 'Checking now.'],
  zh: ['我来确认一下。', '请稍等,正在查看。'],
  ja: ['確認しますね。', 'ちょっと待ってください、確認中です。'],
  es: ['Déjame revisarlo.', 'Un momento, estoy comprobando.'],
}
const FILLER_PHRASES_LONG = {
  ko: ['생각보다 시간이 조금 걸리네요, 거의 다 됐어요.', '아직 찾고 있어요, 조금만 더 기다려 주세요.'],
  en: ['This is taking a bit longer than usual, almost there.', 'Still working on it, just a bit more.'],
  zh: ['比预想的要久一点,马上就好。', '还在查找,请再等一下。'],
  ja: ['少し時間がかかっていますね、もう少しです。', 'まだ確認中です、少々お待ちください。'],
  es: ['Esto está tardando un poco más de lo normal, ya casi.', 'Sigo revisándolo, un poco más.'],
}
function pickFillerPhrase(pool, lang) {
  const candidates = pool[lang] || pool.ko
  return candidates[Math.floor(Math.random() * candidates.length)]
}

let logIdCounter = 0

// "교차 검증해줘" 같은 말로도 교차 검증을 실행할 수 있다(버튼과 동일).
// "카드 닫아줘", "띄운 거 치워줘" 처럼 결과 카드를 없애 달라는 말(메모 삭제 등과 헷갈리지 않도록 '카드/결과/띄운'이 들어갈 때만).
const CARD_CLEAR_PHRASE = /(카드|결과|띄운).{0,8}(닫|없애|지워|치워|숨겨|꺼)/

const CROSS_CHECK_PHRASE = /교차\s*검증|크로스\s*체크|cross[\s-]?check/i

// 교차 검증에 쓸 검색 근거 선택값(서버도 같은 허용 목록으로 검사한다). 마지막 선택은 브라우저에만 기억.
const CHECK_SOURCES = ['both', 'naver', 'google']
function loadCheckSource() {
  try {
    const saved = localStorage.getItem('lumi.checkSource')
    if (CHECK_SOURCES.includes(saved)) return saved
  } catch {
    /* 저장소를 못 쓰는 환경 — 기본값 사용 */
  }
  return 'both'
}

export function LumiProvider({ children }) {
  const { upsertToast, showToast } = useToast()

  // ── 설정 (입력 언어 / 답변 언어 / 음성 성별 / TTS 켜짐) ──
  // 계정(로그인 사용자)에 귀속해 서버 DB에 저장한다(/api/settings) — 브라우저 localStorage가
  // 아니라서 다른 기기/브라우저에서 로그인해도, 서버를 재시작해도 그대로 유지된다.
  // 서버에서 불러오기 전까지는 아래 기본값으로 잠깐 보여준다.
  const [inputLang, setInputLang] = useState('ko')
  const [responseLang, setResponseLang] = useState('auto') // 'auto' | 'ko' | 'en'
  const [voiceGender, setVoiceGender] = useState('female')
  const [ttsEnabled, setTtsEnabled] = useState(true)
  // 버튼을 누르지 않아도 항상 마이크를 켜두고 말하면 바로 대화하는 모드.
  const [alwaysListening, setAlwaysListening] = useState(false)
  // 개인정보 설정: 감정 기록 저장 / Lumi가 먼저 말 걸기(기본 켜짐). 서버(계정)에 저장된다.
  const [emotionLogEnabled, setEmotionLogEnabled] = useState(true)
  const [proactiveCareEnabled, setProactiveCareEnabled] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)
  // 서버에서 설정을 아직 못 불러온 상태에서 저장 effect가 기본값으로 덮어써버리는 걸
  // 막기 위한 가드 — 최초 로드가 끝난 뒤부터만 저장을 시작한다.
  const settingsLoadedRef = useRef(false)

  // 설정이 바뀔 때마다 서버에 저장 — 계정에 귀속되므로 재접속/서버 재시작에도 유지된다.
  useEffect(() => {
    if (!settingsLoadedRef.current) return
    fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
      body: JSON.stringify({
        inputLang, responseLang, voiceGender, ttsEnabled, alwaysListening, emotionLogEnabled, proactiveCareEnabled,
      }),
    }).catch(() => {
      // 저장 실패해도 대화 자체엔 영향 없음 — 조용히 무시
    })
  }, [inputLang, responseLang, voiceGender, ttsEnabled, alwaysListening, emotionLogEnabled, proactiveCareEnabled])

  // 장시간 유지되는 콜백(STT 인식 종료, TTS 재생 등)에서도 항상 최신 설정값을 읽도록
  // ref에 미러링해둔다 — 원본이 매번 <select>.value를 직접 읽던 것과 동일한 효과.
  const settingsRef = useRef({ inputLang, responseLang, voiceGender, ttsEnabled, alwaysListening })
  useEffect(() => {
    settingsRef.current = { inputLang, responseLang, voiceGender, ttsEnabled, alwaysListening }
  }, [inputLang, responseLang, voiceGender, ttsEnabled, alwaysListening])

  function getResponseLanguage() {
    const v = settingsRef.current.responseLang
    return v === 'auto' ? null : v
  }
  function getEffectiveTtsLang() {
    const v = settingsRef.current.responseLang
    return v === 'auto' ? settingsRef.current.inputLang : v
  }

  // ── 코어 비주얼 상태 (마이크 리스닝 / TTS 스피킹 공용) ──
  const [activeState, setActiveState] = useState({ active: false, label: 'STANDBY' })
  function setActiveVisual(active, label) {
    setActiveState({ active, label: active ? label : 'STANDBY' })
  }

  const ttsPlayerRef = useRef(null)
  useEffect(() => {
    const player = ttsPlayerRef.current
    if (!player) return
    const handler = () => setActiveVisual(false, 'LISTENING')
    player.addEventListener('ended', handler)
    player.addEventListener('pause', handler)
    return () => {
      player.removeEventListener('ended', handler)
      player.removeEventListener('pause', handler)
    }
  }, [])

  function isSpeaking() {
    const player = ttsPlayerRef.current
    const audioPlaying = player && !player.paused
    const synthSpeaking = window.speechSynthesis && window.speechSynthesis.speaking
    return !!(audioPlaying || synthSpeaking)
  }

  // ── TTS: Fish Audio 우선, 실패/미설정 시 브라우저 내장 speechSynthesis로 자동 대체 ──
  const voicesRef = useRef([])
  function refreshVoiceCache() {
    if (window.speechSynthesis) voicesRef.current = window.speechSynthesis.getVoices()
  }
  useEffect(() => {
    if (window.speechSynthesis) {
      refreshVoiceCache()
      window.speechSynthesis.onvoiceschanged = refreshVoiceCache
    }
  }, [])

  // OS/브라우저에 설치된 음성 중, 언어가 맞고 이름에서 성별을 유추할 수 있는 걸 고른다.
  // 완벽하진 않다 — 음성 목록이 플랫폼마다 달라서, 못 찾으면 그 언어의 첫 번째 음성을 쓴다.
  function pickBrowserVoice(langCode, gender) {
    if (!voicesRef.current.length) refreshVoiceCache()
    const prefix = langCode.split('-')[0].toLowerCase()
    const candidates = voicesRef.current.filter((v) => v.lang && v.lang.toLowerCase().startsWith(prefix))
    if (!candidates.length) return null
    const femaleHints = ['female', 'yuna', 'heami', 'nara', '여성', 'samantha', 'zira', 'susan', 'aria']
    const maleHints = ['male', 'injoon', 'daniel', 'david', '남성', 'fred', 'alex', 'guy']
    const hints = gender === 'male' ? maleHints : femaleHints
    const match = candidates.find((v) => hints.some((h) => v.name.toLowerCase().includes(h)))
    return match || candidates[0]
  }

  // 재생이 "끝날 때" resolve하는 프로미스를 반환한다 — 상시 듣기 모드가 Lumi의 말이
  // 완전히 끝난 뒤에만 마이크를 다시 켜도록 하기 위해 필요하다.
  function speakBrowser(text) {
    return new Promise((resolve) => {
      if (!window.speechSynthesis) {
        console.error('이 브라우저는 음성 합성을 지원하지 않습니다.')
        resolve()
        return
      }
      window.speechSynthesis.cancel()
      const langCode = sttLangCode(getEffectiveTtsLang())
      const utterance = new SpeechSynthesisUtterance(text)
      utterance.lang = langCode
      const voice = pickBrowserVoice(langCode, settingsRef.current.voiceGender)
      if (voice) utterance.voice = voice
      utterance.onstart = () => setActiveVisual(true, 'SPEAKING')
      utterance.onend = () => { setActiveVisual(false, 'LISTENING'); resolve() }
      utterance.onerror = (event) => {
        console.error('TTS 재생 실패:', event.error)
        setActiveVisual(false, 'LISTENING')
        // 자동재생 정책으로 막힌 경우: 버리지 않고 첫 조작 때 다시 읽는다.
        if (event.error === 'not-allowed' && !blockedSpeechRef.current) {
          blockedSpeechRef.current = { text, url: null }
        }
        resolve()
      }
      window.speechSynthesis.speak(utterance)
    })
  }

  // Lumi의 답변을 우선 Fish Audio로 합성해서 재생하고, 키가 없거나 호출이 실패하면
  // 조용히 브라우저 내장 음성합성으로 넘어간다 — 어느 쪽이든 대화 자체는 끊기지 않는다.
  // 재생이 "시작"할 때가 아니라 "끝날 때" 끝나는 프로미스를 반환한다(상시 듣기 모드에서
  // Lumi 자신의 목소리를 다시 알아듣지 않도록, 말이 다 끝난 뒤에만 마이크를 재개해야 한다).
  // 새로고침 직후처럼 사용자 조작 전이라 브라우저가 소리 재생을 막은 경우, 못 읽은 첫 발화를
  // 보관해 두었다가 첫 클릭/키 입력 때 읽어 준다. (Fish Audio 합성본은 다시 요청하지 않고 그대로 재생)
  const blockedSpeechRef = useRef(null)

  async function playTtsUrl(url) {
    const player = ttsPlayerRef.current
    player.src = url
    setActiveVisual(true, 'SPEAKING')
    await player.play()
    await new Promise((resolve) => {
      const onDone = () => {
        player.removeEventListener('ended', onDone)
        player.removeEventListener('pause', onDone)
        resolve()
      }
      player.addEventListener('ended', onDone)
      player.addEventListener('pause', onDone)
    })
  }

  // Lumi 목소리는 Fish Audio 하나만 쓴다. true로 바꾸면 Fish가 실패했을 때 브라우저 음성으로 대신 읽는다.
  const ALLOW_BROWSER_VOICE = false
  function notifyTtsFailure() {
    const now = Date.now()
    if (now - (window.__lumiTtsToastAt || 0) < 15000) return   // 연달아 실패해도 알림은 15초에 한 번만
    window.__lumiTtsToastAt = now
    showToast('error', 'TTS', 'Fish Audio 음성을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.')
  }

  async function speak(text) {
    if (!settingsRef.current.ttsEnabled || !text) return
    try {
      const res = await fetch('/api/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify({
          text,
          language: getEffectiveTtsLang(),
          gender: settingsRef.current.voiceGender,
        }),
      })
      if (res.ok) {
        const blob = await res.blob()
        const url = URL.createObjectURL(blob)
        try {
          await playTtsUrl(url)
        } catch (playErr) {
          if (playErr?.name === 'NotAllowedError') {
            setActiveVisual(false, 'LISTENING')
            if (!blockedSpeechRef.current) blockedSpeechRef.current = { text, url }
            return
          }
          throw playErr
        }
        return
      }
    } catch (err) {
      console.error('Fish Audio TTS 실패:', err)
    }
    if (ALLOW_BROWSER_VOICE) { await speakBrowser(text); return }
    setActiveVisual(false, 'LISTENING')
    notifyTtsFailure()
  }

  // speak()를 여러 군데(실제 답변, 채움 대사, 알림)에서 겹쳐 호출할 수 있으므로, 재생
  // 순서를 하나의 줄로 직렬화한다 — 안 그러면 나중 호출이 재생 중이던 오디오 엘리먼트의
  // src를 덮어써서 앞 소리가 끊기거나 섞여버린다. 반환하는 프로미스는 "이 텍스트"의
  // 재생이 끝나는 시점에 resolve된다.
  const speechQueueRef = useRef(Promise.resolve())
  function enqueueSpeak(text) {
    const run = () => speak(text)
    const result = speechQueueRef.current.then(run, run)
    speechQueueRef.current = result
    return result
  }

  function toggleTts() {
    setTtsEnabled((prev) => {
      const next = !prev
      if (!next) {
        if (ttsPlayerRef.current) ttsPlayerRef.current.pause()
        if (window.speechSynthesis) window.speechSynthesis.cancel()
      }
      return next
    })
  }

  // 체크박스를 직접 클릭하는 행위 자체가 "사용자 상호작용"이라, 그 안에서 바로
  // recognition.start()를 호출해도 브라우저 정책에 막히지 않는다(toggleTts와 동일한 패턴).
  function toggleAlwaysListening() {
    setAlwaysListening((prev) => {
      const next = !prev
      if (next) {
        if (!isSpeaking()) startListening({ continuous: true })
      } else {
        continuousModeRef.current = false
        if (recognitionRef.current) stopListening()
      }
      return next
    })
  }

  // ── 채팅 (LUMI CONSOLE) ──
  const [chatLog, setChatLog] = useState([])
  const [consoleTag, setConsoleTag] = useState('CONNECTING...')
  const [inputValue, setInputValue] = useState('')
  const [sending, setSending] = useState(false)
  const [pendingImage, setPendingImage] = useState(null) // 보내기 전 첨부 이미지 { base64, mime, previewUrl, name }
  const [checkSource, setCheckSourceState] = useState(loadCheckSource)

  function setCheckSource(value) {
    if (!CHECK_SOURCES.includes(value)) return
    setCheckSourceState(value)
    try {
      localStorage.setItem('lumi.checkSource', value)
    } catch {
      /* 저장소를 못 쓰는 환경 — 이번 세션에서만 유지 */
    }
  }

  function appendLog(sender, text, links, image) {
    const id = ++logIdCounter
    setChatLog((prev) => [...prev, { id, sender, text, emotionLabel: null, links: links || [], image: image || null }])
    return id
  }

  // 사용자가 보낸 메시지 옆에, 감정 분석 모델로 분석된 그 메시지의 감정을 작은 배지로 붙인다.
  function attachEmotionBadge(id, emotion) {
    if (!emotion || !emotion.primaryEmotion) return
    let label = EMOTION_LABEL_KO[emotion.primaryEmotion] || emotion.primaryEmotion
    if (typeof emotion.confidence === 'number') {
      label += ' ' + Math.round(emotion.confidence * 100) + '%'
    }
    // 기분의 방향(valence)에 따라 배지 색을 나눈다: 긍정 pos / 중립 neu / 부정 neg
    const v = typeof emotion.valence === 'number' ? emotion.valence : 0
    const tone = v >= 0.2 ? 'pos' : v <= -0.2 ? 'neg' : 'neu'
    setChatLog((prev) => prev.map((row) => (row.id === id ? { ...row, emotionLabel: label, emotionTone: tone } : row)))
  }

  // 답변에 실제로 반영한 감정 톤(예: "공감 먼저, 차분하게")을 Lumi 답변 옆에 작게 보여준다.
  function attachToneLabel(id, emotion) {
    if (!emotion || typeof emotion.toneLabel !== 'string' || !emotion.toneLabel) return
    setChatLog((prev) => prev.map((row) => (row.id === id ? { ...row, toneLabel: emotion.toneLabel } : row)))
  }

  // 외국어 답변이면 한국어 번역을 화면에만 덧붙인다(음성으로는 읽지 않는다). 번역은 별도 요청이라
  // 영어 답변을 말하는 속도에는 영향이 없고, 실패하면 조용히 없앤다.
  async function requestTranslation(id, text) {
    const patch = (fields) =>
      setChatLog((prev) => prev.map((row) => (row.id === id ? { ...row, ...fields } : row)))
    patch({ translating: true })
    // 번역 모델이 잠깐 바쁠 때(502/503)를 위해 3번까지 다시 시도한다. 400대(없음·한도)는 다시 해도 같으므로 바로 멈춘다.
    let translation = null
    for (let attempt = 1; attempt <= 3 && !translation; attempt++) {
      try {
        const res = await fetch('/api/chat/translate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
          body: JSON.stringify({ text }),
        })
        if (res.ok) {
          const data = await res.json()
          if (data && typeof data.translation === 'string' && data.translation) translation = data.translation
          break
        }
        if (res.status >= 400 && res.status < 500) break
      } catch { /* 네트워크 오류 → 재시도 */ }
      if (attempt < 3) await new Promise((r) => setTimeout(r, 1200 * attempt))
    }
    patch({ translating: false, translation: translation || '번역을 불러오지 못했어요.' })
  }

  async function attachImage(file) {
    try {
      setPendingImage(await prepareImage(file))
    } catch (err) {
      showToast('error', 'IMAGE', err.message)
    }
  }

  function clearImage() {
    setPendingImage(null)
  }

  async function callChat(message, image) {
    const res = await fetch(image ? '/api/chat/image' : '/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
      body: JSON.stringify(image
        ? { message, responseLanguage: getResponseLanguage(), imageBase64: image.base64, imageMime: image.mime }
        : { message, responseLanguage: getResponseLanguage() }),
    })
    if (!res.ok) {
      // 서버의 검증 실패(400)·너무 큰 요청(413)·요청 과다(429) 메시지는 사용자에게 그대로 보여준다.
      let serverMessage = null
      if (res.status === 400 || res.status === 413 || res.status === 429) {
        const body = await res.json().catch(() => null)
        serverMessage = body && typeof body.error === 'string' ? body.error : null
      }
      const err = new Error('HTTP ' + res.status)
      err.userMessage = serverMessage
      throw err
    }
    return await res.json() // { reply, emotion }
  }

  // 결과 중 판정·요약 두 줄만 음성으로 읽는다(전체는 길어서 화면에만 표시).
  function crossCheckSpeech(detail) {
    const lines = String(detail).split('\n').filter((l) => /^\s*(판정|요약)\s*[:：]/.test(l))
    if (!lines.length) return ''
    return '교차 검증 결과입니다. ' + lines.map((l) => l.replace(/\s*[:：]\s*/, ' ').trim()).join('. ')
  }

  // 교차 검증: 서버가 보관 중인 직전 질문/답변(+검색 근거)을 네이버 검색 근거와 대조해 Gemini가 다시 검토한다.
  // 검색·모델 호출이 추가로 생기고 지연이 있으므로 버튼/요청 문구로만 실행한다. 검증 대상은
  // 서버가 정하므로 요청 본문은 보내지 않는다.
  async function crossCheck() {
    if (sending) return
    pauseListeningForResponse() // 결과를 읽어주는 동안 마이크가 자기 목소리를 다시 알아듣지 않도록
    setSending(true)
    setConsoleTag('CROSS-CHECK...')
    try {
      const res = await fetch('/api/cross-check?source=' + encodeURIComponent(checkSource), {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      const body = await res.json().catch(() => null)
      if (!res.ok) {
        throw new Error(body && typeof body.error === 'string' ? body.error : '교차 검증에 실패했습니다.')
      }
      appendLog('check', '[교차 검증 · ' + body.model + '] ' + body.detail)
      const spoken = crossCheckSpeech(body.detail)
      if (spoken) await enqueueSpeak(spoken)
    } catch (err) {
      appendLog('check', err instanceof TypeError ? '서버에 연결하지 못했습니다.' : err.message)
    } finally {
      setSending(false)
      setConsoleTag('STANDBY')
      resumeListeningIfContinuous()
    }
  }

  // overrideText가 주어지면(STT 자동 전송) 그 값을, 아니면 현재 입력창 값을 보낸다.
  async function sendMessage(overrideText) {
    const message = (overrideText !== undefined ? overrideText : inputValue).trim()
    // 첨부해 둔 이미지가 있으면 직접 입력이든 음성이든 이번 메시지와 함께 보낸다.
    const image = pendingImage
    if (!message && !image) return
    if (!image && CARD_CLEAR_PHRASE.test(message) && message.length <= 30) {
      setInputValue('')
      appendLog('user', message)
      window.dispatchEvent(new CustomEvent('lumi:cards-clear'))
      const done = '결과 카드를 치웠어요.'
      appendLog('lumi', done)
      await enqueueSpeak(done)
      return
    }
    if (!image && CROSS_CHECK_PHRASE.test(message) && message.length <= 30) {
      setInputValue('')
      appendLog('user', message)
      await crossCheck()
      return
    }
    pauseListeningForResponse() // 응답을 기다리는 동안 마이크가 자기 말을 다시 알아듣지 않도록
    const userRowId = appendLog('user', message || '(이미지)', null, image ? image.previewUrl : null)
    setInputValue('')
    if (image) setPendingImage(null)
    setSending(true)
    setConsoleTag('THINKING...')

    // Gemini 응답이 늦어질수록(특히 일정/리마인더/웹 검색처럼 함수 호출이 섞이면) 아무
    // 소리도 없이 멈춰 있는 시간이 길어진다 — 1.2초 안에 응답이 오면(대부분의 짧은 대화)
    // 끼어들지 않도록 타이머를 취소하고, 그보다 오래 걸리면 짧은 채움 대사를 먼저
    // 들려준다. 6초가 더 지나도 안 끝나면("아직 좀 걸리네요" 류) 한 번 더 안내한다.
    const lang = getEffectiveTtsLang()
    const shortTimer = setTimeout(() => {
      enqueueSpeak(pickFillerPhrase(FILLER_PHRASES_SHORT, lang))
    }, 1200)
    const longTimer = setTimeout(() => {
      enqueueSpeak(pickFillerPhrase(FILLER_PHRASES_LONG, lang))
    }, 7200)

    try {
      const data = await callChat(message, image)
      clearTimeout(shortTimer)
      clearTimeout(longTimer)
      attachEmotionBadge(userRowId, data.emotion)
      const lumiRowId = appendLog('lumi', data.reply, data.links)
      // 서버가 조회·검색 결과를 카드로 내려주면(cards 배열) 코어 주변에 띄운다.
      if (Array.isArray(data.cards)) {
        data.cards.forEach((card) => window.dispatchEvent(new CustomEvent('lumi:card', { detail: card })))
      }
      attachToneLabel(lumiRowId, data.emotion)
      if (data.translatable) requestTranslation(lumiRowId, data.reply)
      await enqueueSpeak(data.reply) // 채움 대사 뒤로 줄을 서서, 끝까지 재생되길 기다린 뒤에만 상시 듣기를 재개한다
    } catch (err) {
      clearTimeout(shortTimer)
      clearTimeout(longTimer)
      appendLog('lumi', err.userMessage || '통신 중 문제가 발생했습니다. 서버 실행 상태를 확인해주세요.')
      console.error(err)
    } finally {
      setSending(false)
      setConsoleTag('STANDBY')
      refreshSchedules()
      refreshUsage()
      refreshMood()
      refreshNotes()
      refreshSecurity()
      refreshReminders()
      resumeListeningIfContinuous()
    }
  }

  // ── TODAY'S SCHEDULE / API 사용량 대시보드 ──
  // 최초 조회 전(null)과 조회했지만 0건인 상태를 구분해서 "불러오는 중" 표시를 유지한다.
  const [schedules, setSchedules] = useState(null)
  const [usage, setUsage] = useState({ today: '-', dailyLimit: '-', last7Days: [] })

  async function refreshSchedules() {
    try {
      const res = await fetch('/api/schedules')
      if (!res.ok) throw new Error('HTTP ' + res.status)
      const data = await res.json()
      setSchedules(data.schedules || [])
      upsertToast('schedule', 'success', 'SCHEDULE FETCH OK', '일정 조회 성공')
    } catch (err) {
      console.error('일정 조회 실패:', err)
      upsertToast('schedule', 'error', 'SCHEDULE FETCH FAILED', '일정 조회 실패: ' + err.message)
    }
  }

  // 일정 수정·삭제(서버가 본인 일정만 허용). 실패하면 사유를 문자열로 돌려준다(성공이면 null).
  async function scheduleRequest(method, url, body) {
    const res = await fetch(url, {
      method,
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), 'X-XSRF-TOKEN': getCsrfToken() },
      body: body ? JSON.stringify(body) : undefined,
    })
    let data = null
    try { data = await res.json() } catch { /* 본문 없음 */ }
    if (!res.ok) throw new Error((data && data.error) || 'HTTP ' + res.status)
    return data
  }
  async function updateSchedule(id, { title, startTime, endTime }) {
    try {
      await scheduleRequest('PUT', '/api/schedules/' + id, { title, startTime, endTime: endTime || null })
      upsertToast('schedule', 'success', 'SCHEDULE UPDATED', '일정을 수정했습니다')
      await refreshSchedules()
      return null
    } catch (err) {
      return err.message
    }
  }
  async function deleteSchedule(id) {
    try {
      await scheduleRequest('DELETE', '/api/schedules/' + id)
      upsertToast('schedule', 'success', 'SCHEDULE DELETED', '일정을 삭제했습니다')
    } catch (err) {
      upsertToast('schedule', 'error', 'SCHEDULE DELETE FAILED', '삭제 실패: ' + err.message)
    }
    await refreshSchedules()
  }
  async function deleteScheduleSeries(title) {
    try {
      const r = await scheduleRequest('DELETE', '/api/schedules?title=' + encodeURIComponent(title))
      upsertToast('schedule', 'success', 'SCHEDULE DELETED', "'" + title + "' " + r.deleted + '건을 삭제했습니다')
    } catch (err) {
      upsertToast('schedule', 'error', 'SCHEDULE DELETE FAILED', '삭제 실패: ' + err.message)
    }
    await refreshSchedules()
  }

  async function refreshUsage() {
    try {
      const res = await fetch('/api/usage')
      if (!res.ok) throw new Error('HTTP ' + res.status)
      const data = await res.json()
      setUsage({ today: data.today, dailyLimit: data.dailyLimit, last7Days: data.last7Days || [] })
      upsertToast('usage', 'success', 'USAGE FETCH OK', 'API 사용량 조회 성공')
    } catch (err) {
      console.error('API 사용량 조회 실패:', err)
      upsertToast('usage', 'error', 'USAGE FETCH FAILED', 'API 사용량 조회 실패: ' + err.message)
    }
  }

  // ── 감정 시계열(MOOD · 7 DAYS) / 지식 노트(MEMORY NOTES) ──
  // 화면 갱신용 조회라서 성공 토스트는 띄우지 않는다(대화마다 뜨면 방해).
  const [moodDays, setMoodDays] = useState([])
  const [notes, setNotes] = useState([])

  async function refreshMood() {
    try {
      const res = await fetch('/api/emotions/daily?days=7')
      if (!res.ok) return
      const data = await res.json()
      setMoodDays(data.days || [])
    } catch (err) {
      console.error('감정 기록 조회 실패:', err)
    }
  }

  // 현재 보안 상태(SECURITY STATUS) — 본인 계정의 최근 24시간 탐지·차단 요약
  const [secStatus, setSecStatus] = useState(null)

  async function refreshSecurity() {
    try {
      const res = await fetch('/api/security/status')
      if (!res.ok) return
      const data = await res.json()
      // 모양이 다른 응답(프록시 오류 페이지 등)이면 패널을 건드리지 않는다
      if (!data || typeof data.level !== 'string') return
      setSecStatus({
        ...data,
        categories: Array.isArray(data.categories) ? data.categories : [],
        protections: Array.isArray(data.protections) ? data.protections : [],
        recent: Array.isArray(data.recent) ? data.recent : [],
      })
    } catch (err) {
      console.error('보안 상태 조회 실패:', err)
    }
  }

  // 다가오는 리마인더(UPCOMING REMINDERS / 요약 카드). 화면 갱신용이라 성공 토스트는 없음.
  // null = 아직 조회 전, [] = 조회했지만 없음
  const [reminders, setReminders] = useState(null)

  async function refreshReminders() {
    try {
      const res = await fetch('/api/reminders')
      if (!res.ok) return
      const data = await res.json()
      setReminders(data.reminders || [])
    } catch (err) {
      console.error('리마인더 조회 실패:', err)
    }
  }

  async function refreshNotes() {
    try {
      const res = await fetch('/api/notes')
      if (!res.ok) return
      const data = await res.json()
      setNotes(data.notes || [])
    } catch (err) {
      console.error('노트 조회 실패:', err)
    }
  }

  async function addNote(text) {
    const content = (text || '').trim()
    if (!content) return false
    try {
      const res = await fetch('/api/notes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
        body: JSON.stringify({ content }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) {
        showToast('error', 'NOTE', data.error || '노트를 저장하지 못했습니다.')
        return false
      }
      await refreshNotes()
      return true
    } catch (err) {
      console.error('노트 저장 실패:', err)
      showToast('error', 'NOTE', '노트를 저장하지 못했습니다.')
      return false
    }
  }

  async function deleteNote(id) {
    try {
      const res = await fetch('/api/notes/' + encodeURIComponent(id), {
        method: 'DELETE',
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
      })
      if (!res.ok) showToast('error', 'NOTE', '노트를 삭제하지 못했습니다.')
      await refreshNotes()
    } catch (err) {
      console.error('노트 삭제 실패:', err)
    }
  }

  async function clearNotes() {
    try {
      await fetch('/api/notes', { method: 'DELETE', headers: { 'X-XSRF-TOKEN': getCsrfToken() } })
      await refreshNotes()
    } catch (err) {
      console.error('노트 전체 삭제 실패:', err)
    }
  }

  async function clearMoodLog() {
    try {
      const res = await fetch('/api/emotions', { method: 'DELETE', headers: { 'X-XSRF-TOKEN': getCsrfToken() } })
      if (res.ok) showToast('success', 'MOOD', '감정 기록을 모두 삭제했습니다.')
      await refreshMood()
    } catch (err) {
      console.error('감정 기록 삭제 실패:', err)
    }
  }

  // 서버는 각 일정을 개별로만 알고 있으므로(hasConflict는 add 시점에만 계산),
  // 화면에 표시할 전체 목록 기준 겹침은 여기서 다시 한 번 계산한다.
  const scheduleView = useMemo(() => {
    if (schedules === null) return { rows: [], count: null, conflictAlert: null }
    if (!schedules.length) return { rows: [], count: 0, conflictAlert: null }
    const sorted = [...schedules].sort((a, b) => new Date(a.startTime) - new Date(b.startTime))
    const now = new Date()
    const conflicts = []
    for (let i = 0; i < sorted.length; i++) {
      const aStart = new Date(sorted[i].startTime)
      const aEnd = sorted[i].endTime ? new Date(sorted[i].endTime) : new Date(aStart.getTime() + 3600000)
      for (let j = i + 1; j < sorted.length; j++) {
        const bStart = new Date(sorted[j].startTime)
        const bEnd = sorted[j].endTime ? new Date(sorted[j].endTime) : new Date(bStart.getTime() + 3600000)
        if (aStart < bEnd && bStart < aEnd) conflicts.push([sorted[i], sorted[j]])
      }
    }
    let nextAssigned = false
    const rows = sorted.map((s) => {
      const start = new Date(s.startTime)
      const isNext = !nextAssigned && start >= now
      if (isNext) nextAssigned = true
      const hasConflict = conflicts.some(([a, b]) => a === s || b === s)
      return { ...s, _start: start, isNext, hasConflict }
    })
    let conflictAlert = null
    if (conflicts.length > 0) {
      const [a, b] = conflicts[0]
      conflictAlert = a.title + '과(와) ' + b.title + ' 일정이 겹칩니다. 조정이 필요할까요?'
    }
    return { rows, count: rows.length, conflictAlert }
  }, [schedules])

  // ── 마이크 (STT, Web Speech API) ──
  const recognitionRef = useRef(null)
  const [recognizing, setRecognizing] = useState(false)
  // 상시 듣기 모드: 켜져 있으면 한 번의 발화가 끝나도(onend) 자동으로 다시 듣기를 시작한다.
  const continuousModeRef = useRef(false)
  // 사용자가 마이크를 눌러 "일시정지"한 경우 onend에서 자동 재시작하지 않도록 구분한다
  // (자연스럽게 침묵으로 끝난 것과 수동으로 멈춘 것을 구별).
  const manualStopRef = useRef(false)

  // 브라우저 내장 Web Speech API로 음성 인식(STT)을 수행한다. Chrome/Edge 지원,
  // Firefox는 미지원이며 이 경우 에러 토스트로 안내한다. 언어는 TTS 언어 선택과 공유한다.
  // opts.continuous === true면 "상시 듣기" 모드: 한 번 듣고 끝내는 게 아니라, 발화가 끝날
  // 때마다(onend) 자동으로 다시 듣기 시작해서 버튼 없이도 계속 대화할 수 있게 한다.
  function startListening(opts) {
    const continuous = !!(opts && opts.continuous)
    if (isSpeaking()) {
      // Lumi가 말하는 중엔 자기 목소리를 자기가 알아듣지 않도록 지금은 시작하지 않고,
      // 상시 모드라면 말이 끝난 뒤 다시 시도한다(수동 클릭이면 그냥 안내만 하고 끝).
      if (continuous) {
        continuousModeRef.current = true
        setTimeout(() => {
          if (continuousModeRef.current && !recognitionRef.current) startListening({ continuous: true })
        }, 500)
        return
      }
      showToast('error', 'MIC BUSY', 'Lumi가 말하는 중에는 음성 인식을 시작할 수 없습니다.')
      return
    }
    const NativeSpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
    // 설정에서 '서버 인식'을 고르고 서버가 준비돼 있으면 OpenAI 전사 엔진, 아니면 브라우저 기본 엔진
    const SpeechRecognitionImpl = serverStt.pick(NativeSpeechRecognition)
    if (!SpeechRecognitionImpl) {
      showToast('error', 'STT UNAVAILABLE', '이 브라우저는 음성 인식을 지원하지 않습니다 (Chrome 권장).')
      return
    }

    manualStopRef.current = false
    continuousModeRef.current = continuous

    const recognition = new SpeechRecognitionImpl()
    recognition.lang = sttLangCode(settingsRef.current.inputLang)
    recognition.interimResults = true
    recognition.maxAlternatives = 1

    let latestTranscript = ''

    recognition.onresult = (event) => {
      let transcript = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        transcript += event.results[i][0].transcript
      }
      latestTranscript = transcript
      setInputValue(transcript)
    }

    recognition.onerror = (event) => {
      console.error('STT 오류:', event.error)
      // 상시 듣기 모드에서는 침묵(no-speech)이 흔하게 발생하므로 그때마다 토스트를 띄우지 않는다.
      if (event.error !== 'no-speech' && event.error !== 'aborted') {
        showToast('error', 'STT ERROR', '음성 인식 오류: ' + event.error)
      }
    }

    // 침묵이 감지되면 브라우저가 자동으로 인식을 종료한다 — 이때 인식된 문장을 바로 전송하고,
    // 상시 듣기 모드라면(그리고 수동으로 멈춘 게 아니라면) 다시 듣기를 이어간다.
    recognition.onend = () => {
      recognitionRef.current = null
      setRecognizing(false)
      setActiveVisual(false, 'LISTENING')
      if (manualStopRef.current) {
        manualStopRef.current = false
        return
      }
      if (latestTranscript.trim()) {
        sendMessage(latestTranscript) // sendMessage가 응답/음성 재생이 끝난 뒤 상시 모드면 다시 듣기를 이어간다.
        return
      }
      if (continuousModeRef.current) {
        setTimeout(() => {
          if (continuousModeRef.current && !manualStopRef.current) startListening({ continuous: true })
        }, 400)
      }
    }

    try {
      recognition.start()
      recognitionRef.current = recognition
      setRecognizing(true)
      setActiveVisual(true, continuous ? 'ALWAYS LISTENING' : 'LISTENING')
    } catch (err) {
      console.error('STT 시작 실패:', err)
      showToast('error', 'STT START FAILED', '음성 인식을 시작하지 못했습니다: ' + err.message)
    }
  }

  function stopListening() {
    manualStopRef.current = true
    if (recognitionRef.current) recognitionRef.current.stop()
  }

  function toggleMic() {
    if (recognizing) stopListening()
    else startListening({ continuous: settingsRef.current.alwaysListening })
  }

  // 응답을 기다리거나(THINKING) Lumi가 말하는 동안(SPEAKING)은 자기 목소리를 자기가 다시
  // 알아듣지 않도록 잠깐 멈추고, 끝나면 상시 모드였을 때만 다시 듣기 시작한다.
  function pauseListeningForResponse() {
    if (recognitionRef.current) stopListening()
  }
  function resumeListeningIfContinuous() {
    if (continuousModeRef.current && !recognitionRef.current && !isSpeaking()) {
      setTimeout(() => {
        if (continuousModeRef.current && !recognitionRef.current) startListening({ continuous: true })
      }, 300)
    }
  }

  // 환영 인사는 페이지에 들어오자마자 바로 나와야 한다 — 클릭/키 입력 등 아무 상호작용도
  // 기다리지 않고 즉시 말한다. 브라우저 자동재생 정책상 상호작용 전에는 소리가 막힐 수도
  // 있지만(그 경우 이 한 번만 조용히 실패하고, 텍스트는 appendLog로 이미 보여준 상태라
  // 대화 자체엔 지장이 없다), 허용되는 브라우저/상황에서는 아무것도 안 해도 바로 들린다.
  // firstInteractionHandledRef는 "사용자가 페이지에 처음 손댄 순간"을 한 번만 잡아서,
  // 그 순간 오디오 재생 권한 자체를 풀어두는 데만 쓴다 — 그 뒤로 이어지는 실제 대화 응답
  // 음성(두 번째 메시지부터)이 안전하게 재생되게 하기 위함이다.
  const firstInteractionHandledRef = useRef(false)

  // "상호작용이 한 번 있었다"는 사실만으로는 부족하다 — 그 상호작용 핸들러 "안에서 곧바로"
  // 재생을 시작한 게 아니라, fetch 같은 비동기 작업을 한 번 거친 뒤에야 play()/speak()를
  // 호출하면 일부 브라우저(특히 speechSynthesis)는 그 사이에 활성화가 끊겼다고 보고 다시
  // NotAllowedError로 막아버린다. 그래서 사용자가 페이지에 처음 손댄 "그 순간" 핸들러
  // 안에서, 소리 없는 더미 재생을 동기적으로 한 번 실행해 오디오 엘리먼트와 음성합성 엔진
  // 둘 다를 미리 "풀어"둔다. 이렇게 한 번 진짜로 재생이 성공하고 나면, 그 뒤로 fetch를
  // 거쳐 늦게 호출하는 실제 인사말 재생도 더 이상 막히지 않는다.
  function unlockAudioPlayback() {
    try {
      const player = ttsPlayerRef.current
      if (player) {
        const prevMuted = player.muted
        player.muted = true
        const playPromise = player.play()
        if (playPromise && typeof playPromise.catch === 'function') playPromise.catch(() => {})
        player.pause()
        player.currentTime = 0
        player.muted = prevMuted
      }
    } catch {
      // 무시 — 실패해도 음성합성 쪽 풀기는 별개로 계속 진행한다
    }
    try {
      if (window.speechSynthesis) {
        const unlockUtterance = new SpeechSynthesisUtterance(' ')
        unlockUtterance.volume = 0
        window.speechSynthesis.speak(unlockUtterance)
      }
    } catch {
      // 무시
    }
  }

  function replayBlockedSpeech() {
    const pending = blockedSpeechRef.current
    blockedSpeechRef.current = null
    if (!pending) return
    const run = () => (pending.url ? playTtsUrl(pending.url).catch(() => setActiveVisual(false, 'LISTENING')) : speakBrowser(pending.text))
    speechQueueRef.current = speechQueueRef.current.then(run, run)
  }

  function armFirstInteraction() {
    if (firstInteractionHandledRef.current) return
    const handler = () => {
      if (firstInteractionHandledRef.current) return
      firstInteractionHandledRef.current = true
      unlockAudioPlayback()
      replayBlockedSpeech()
      document.removeEventListener('pointerdown', handler)
      document.removeEventListener('keydown', handler)
      if (settingsRef.current.alwaysListening && !recognitionRef.current) {
        startListening({ continuous: true })
      }
    }
    document.addEventListener('pointerdown', handler, { once: true })
    document.addEventListener('keydown', handler, { once: true })
  }

  // Lumi가 사용자 입력 없이 먼저 말을 걸어야 할 내용(리마인더/일정 임박 등)이 있는지
  // 주기적으로 확인한다 — 메시지 입력창에 포커스가 있는지와 전혀 무관하게 항상 동작한다.
  async function announceProactive(text, translation) {
    pauseListeningForResponse()
    const rowId = appendLog('lumi', text)
    // 외국어 문구에 서버가 한국어 번역을 같이 주면 화면에만 덧붙인다(음성으로는 읽지 않는다).
    if (typeof translation === 'string' && translation) {
      setChatLog((prev) => prev.map((row) => (row.id === rowId ? { ...row, translation } : row)))
    }
    await enqueueSpeak(text)
    resumeListeningIfContinuous()
  }

  useEffect(() => {
    async function pollNotifications() {
      try {
        const res = await fetch('/api/notifications/pending')
        if (res.status === 401) {
          // 서버가 세션을 폐기함(유휴 만료·다른 기기에서 사용 감지) → 로그인 화면으로
          window.dispatchEvent(new Event('lumi:session-expired'))
          return
        }
        if (!res.ok) return
        const data = await res.json()
        const items = data.notifications || []
        for (const n of items) {
          await announceProactive(n.message, n.translation)
        }
      } catch (err) {
        console.error('알림 조회 실패:', err)
      }
    }
    const timer = setInterval(pollNotifications, 30000)
    // 보안 상태는 대화 없이도 바뀔 수 있어(세션 만료·다른 탭 등) 1분마다 갱신
    const secTimer = setInterval(refreshSecurity, 60000)
    return () => { clearInterval(timer); clearInterval(secTimer) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 페이지 로드 시: 1) 서버에 저장된 설정을 먼저 불러오고 2) 인사 + 일정 브리핑을 자동 요청
  // (페르소나 시스템 프롬프트 §4 규칙). 설정을 먼저 끝내야 인사말의 언어 힌트가 저장된
  // 설정(답변 언어 등)을 바로 반영한다.
  const hasGreetedRef = useRef(false)
  useEffect(() => {
    if (hasGreetedRef.current) return
    hasGreetedRef.current = true
    armFirstInteraction()
    ;(async function initApp() {
      try {
        const res = await fetch('/api/settings')
        if (res.ok) {
          const data = await res.json()
          if (data.inputLang) setInputLang(data.inputLang)
          if (data.responseLang) setResponseLang(data.responseLang)
          if (data.voiceGender) setVoiceGender(data.voiceGender)
          if (typeof data.ttsEnabled === 'boolean') setTtsEnabled(data.ttsEnabled)
          if (typeof data.alwaysListening === 'boolean') setAlwaysListening(data.alwaysListening)
          if (typeof data.emotionLogEnabled === 'boolean') setEmotionLogEnabled(data.emotionLogEnabled)
          if (typeof data.proactiveCareEnabled === 'boolean') setProactiveCareEnabled(data.proactiveCareEnabled)
          // settingsRef는 별도 effect로만 다음 렌더에 갱신되는데, 바로 이어지는 인사말
          // 요청엔 그걸 기다릴 시간이 없으므로 여기서 즉시 동기화해둔다.
          settingsRef.current = {
            inputLang: data.inputLang || settingsRef.current.inputLang,
            responseLang: data.responseLang || settingsRef.current.responseLang,
            voiceGender: data.voiceGender || settingsRef.current.voiceGender,
            ttsEnabled: typeof data.ttsEnabled === 'boolean' ? data.ttsEnabled : settingsRef.current.ttsEnabled,
            alwaysListening: typeof data.alwaysListening === 'boolean' ? data.alwaysListening : settingsRef.current.alwaysListening,
          }
        }
      } catch {
        // 서버에서 못 불러와도 기본값으로 계속 진행
      } finally {
        settingsLoadedRef.current = true
      }

      setConsoleTag('CONNECTING...')
      try {
        const data = await callChat('(대화 시작)')
        const greetId = appendLog('lumi', data.reply)
        if (data.translatable) requestTranslation(greetId, data.reply)
        // 클릭/키 입력 등 아무 상호작용도 기다리지 않고 바로 말한다. 브라우저가 아직
        // 자동재생을 막아둔 상태라면 이번 한 번은 소리 없이 조용히 실패하지만(텍스트는
        // 위에서 이미 보여줬으므로 대화 자체엔 지장 없음), 그 뒤로 사용자가 아무 데나 한
        // 번 클릭/입력하면 armFirstInteraction()이 재생 권한을 풀어줘서 다음 응답부터는
        // 정상 재생된다.
        enqueueSpeak(data.reply)
      } catch (err) {
        appendLog('lumi', '서버에 연결하지 못했습니다. Spring Boot 애플리케이션이 실행 중인지 확인해주세요.')
        console.error(err)
      } finally {
        setConsoleTag('STANDBY')
        refreshSchedules()
        refreshUsage()
        refreshMood()
        refreshNotes()
        refreshSecurity()
        refreshReminders()
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const value = {
    // 코어 비주얼
    activeState,
    ttsPlayerRef,

    // 설정
    inputLang, setInputLang,
    responseLang, setResponseLang,
    voiceGender, setVoiceGender,
    ttsEnabled, toggleTts,
    alwaysListening, toggleAlwaysListening,
    emotionLogEnabled, setEmotionLogEnabled,
    proactiveCareEnabled, setProactiveCareEnabled,
    settingsOpen, openSettings: () => setSettingsOpen(true), closeSettings: () => setSettingsOpen(false),

    // 마이크
    recognizing, toggleMic,

    // 채팅
    chatLog, consoleTag, inputValue, setInputValue, sending, sendMessage, crossCheck, checkSource, setCheckSource,
    pendingImage, attachImage, clearImage,

    // 대시보드
    schedules: scheduleView, usage, updateSchedule, deleteSchedule, deleteScheduleSeries,
    moodDays, refreshMood, clearMoodLog,
    secStatus, refreshSecurity,
    reminders, refreshReminders,
    notes, addNote, deleteNote, clearNotes,
  }

  return <LumiContext.Provider value={value}>{children}</LumiContext.Provider>
}

export function useLumi() {
  const ctx = useContext(LumiContext)
  if (!ctx) throw new Error('useLumi must be used within a LumiProvider')
  return ctx
}
