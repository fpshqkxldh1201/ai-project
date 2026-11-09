import { useState } from 'react'
import { serverStt } from '../lib/serverStt'
import { useLumi } from '../context/LumiContext.jsx'

// 음성 인식 엔진 선택(이 브라우저에만 저장). '서버 인식'은 서버에 OPENAI_API_KEY가 있어야 켜진다.
function SttEngineRow() {
  const [engine, setEngine] = useState(serverStt.getEngine())
  const [note, setNote] = useState('')
  async function onChange(e) {
    const v = e.target.value
    serverStt.setEngine(v)
    setEngine(v)
    if (v === 'server') {
      const ok = await serverStt.refreshStatus()
      setNote(ok ? '' : '서버 음성 인식을 쓸 수 없어요(서버의 OPENAI_API_KEY 확인). 브라우저 인식으로 계속 동작해요.')
    } else {
      setNote('')
    }
  }
  return (
    <div className="settings-row">
      <label htmlFor="sttEngineSelect">음성 인식 엔진</label>
      <select id="sttEngineSelect" value={engine} onChange={onChange}>
        <option value="browser">브라우저 내장 (기본, 무료)</option>
        <option value="server">서버 인식 (OpenAI, 더 정확)</option>
      </select>
      {note && <small>{note}</small>}
    </div>
  )
}

export default function SettingsModal() {
  const {
    settingsOpen, closeSettings,
    inputLang, setInputLang,
    responseLang, setResponseLang,
    voiceGender, setVoiceGender,
    emotionLogEnabled, setEmotionLogEnabled,
    proactiveCareEnabled, setProactiveCareEnabled,
    clearMoodLog,
  } = useLumi()

  return (
    <div
      className={'settings-modal' + (settingsOpen ? ' open' : '')}
      onClick={(e) => { if (e.target === e.currentTarget) closeSettings() }}
    >
      <div className="settings-panel">
        <div className="settings-header">
          <span>SETTINGS</span>
          <button className="settings-close" type="button" onClick={closeSettings}>✕</button>
        </div>
        <div className="settings-row">
          <label htmlFor="inputLangSelect">입력 언어 (음성 인식)</label>
          <select id="inputLangSelect" value={inputLang} onChange={(e) => setInputLang(e.target.value)}>
            <option value="ko">한국어</option>
            <option value="en">English</option>
            <option value="zh">中文</option>
            <option value="ja">日本語</option>
            <option value="es">Español</option>
          </select>
        </div>
        <div className="settings-row">
          <label htmlFor="responseLangSelect">AI 답변 언어</label>
          <select id="responseLangSelect" value={responseLang} onChange={(e) => setResponseLang(e.target.value)}>
            <option value="auto">자동 (사용자와 동일한 언어)</option>
            <option value="ko">한국어로 항상 답변</option>
            <option value="en">영어로 항상 답변</option>
          </select>
        </div>
        <SttEngineRow />
        <div className="settings-row">
          <label htmlFor="voiceGenderSelect">음성 성별</label>
          <select id="voiceGenderSelect" value={voiceGender} onChange={(e) => setVoiceGender(e.target.value)}>
            <option value="female">여성</option>
            <option value="male">남성</option>
          </select>
        </div>
        <div className="settings-row toggle">
          <label htmlFor="emotionLogToggle">
            감정 기록 저장
            <small>감정 수치만 저장해요(대화 내용·사진은 저장 안 함). 90일 뒤 자동 삭제돼요.</small>
          </label>
          <input id="emotionLogToggle" type="checkbox" checked={emotionLogEnabled}
            onChange={(e) => setEmotionLogEnabled(e.target.checked)} />
        </div>
        <div className="settings-row toggle">
          <label htmlFor="proactiveCareToggle">
            먼저 말 걸기
            <small>기분이 계속 가라앉아 보이면 Lumi가 먼저 안부를 건네요.</small>
          </label>
          <input id="proactiveCareToggle" type="checkbox" checked={proactiveCareEnabled}
            onChange={(e) => setProactiveCareEnabled(e.target.checked)} />
        </div>
        <div className="settings-row">
          <button className="settings-danger" type="button"
            onClick={() => { if (window.confirm('감정 기록을 모두 삭제할까요?')) clearMoodLog() }}>
            감정 기록 모두 삭제
          </button>
        </div>
      </div>
    </div>
  )
}
