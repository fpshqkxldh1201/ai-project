import { Fragment, useEffect, useRef, useState } from 'react'
import { useLumi } from '../context/LumiContext.jsx'
import { IMAGE_ACCEPT } from '../utils/imageUpload.js'

export default function ConsoleSection() {
  const {
    consoleTag, chatLog, inputValue, setInputValue, sending, sendMessage, crossCheck, checkSource, setCheckSource,
    ttsEnabled, toggleTts, openSettings, pendingImage, attachImage, clearImage,
  } = useLumi()

  const logRef = useRef(null)
  const fileRef = useRef(null)
  const [dragging, setDragging] = useState(false)

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [chatLog])

  function handleSend() {
    sendMessage()
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') sendMessage()
  }

  function pickFile(e) {
    const file = e.target.files && e.target.files[0]
    e.target.value = '' // 같은 파일을 다시 고를 수 있게
    if (file) attachImage(file)
  }

  // 스크린샷을 Ctrl+V로 붙여넣기
  function handlePaste(e) {
    const item = Array.from(e.clipboardData?.items || []).find((i) => i.kind === 'file' && i.type.startsWith('image/'))
    if (item) {
      e.preventDefault()
      attachImage(item.getAsFile())
    }
  }

  function handleDrop(e) {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer?.files?.[0]
    if (file) attachImage(file)
  }

  return (
    <section
      className={'hud-panel console-panel' + (dragging ? ' dragging' : '')}
      onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
      onDragLeave={() => setDragging(false)}
      onDrop={handleDrop}
    >
      <div className="panel-header">
        <span>LUMI CONSOLE</span>
        <div className="console-header-controls">
          <button className="tts-toggle" type="button" title="설정" onClick={openSettings}>⚙️</button>
          <button
            className={'tts-toggle' + (ttsEnabled ? '' : ' muted')}
            type="button"
            title="음성 출력 켜기/끄기"
            onClick={toggleTts}
          >
            {ttsEnabled ? '🔊' : '🔇'}
          </button>
          <span className="tag">{consoleTag}</span>
        </div>
      </div>
      <div className="console-log" ref={logRef}>
        {chatLog.map((row) => {
          // 유튜브 영상·구글 검색 출처 카드. 서버가 https 링크만 내려주지만 여기서도 한 번 더 거른다.
          const safeLinks = (row.links || []).filter(
            (l) => l && typeof l.url === 'string' && l.url.startsWith('https://'),
          )
          return (
            <Fragment key={row.id}>
              <div className={'log-row log-' + row.sender}>
                <span className="log-tag">{row.sender === 'user' ? 'YOU' : row.sender === 'check' ? 'CHECK' : 'LUMI'}</span>
                <span className="log-text">{row.text}</span>
                {row.image && <img className="log-thumb" src={row.image} alt="첨부한 이미지" />}
                {row.emotionLabel && (
                  <span className={'log-emotion ' + (row.emotionTone || 'neu')}>{row.emotionLabel}</span>
                )}
                {row.toneLabel && (
                  <span className="log-tone" title="감정 분석 결과를 반영해 조절한 답변 톤">{row.toneLabel}</span>
                )}
              </div>
              {(row.translating || row.translation) && (
                <div className="log-row log-ko">
                  <span className="log-tag">KO</span>
                  <span className={'log-text' + (row.translating ? ' pending' : '')}>
                    {row.translating ? '번역 중…' : row.translation}
                  </span>
                </div>
              )}
              {safeLinks.length > 0 && (
                <div className="log-links">
                  {safeLinks.map((l) => (
                    <a className="log-link" key={l.url} href={l.url} target="_blank" rel="noopener noreferrer">
                      <span className="log-link-kind">{l.kind === 'youtube' ? 'YOUTUBE' : 'SOURCE'}</span>
                      <span>{l.title || l.url}</span>
                      {l.subtitle && <span className="log-link-sub">{l.subtitle}</span>}
                    </a>
                  ))}
                </div>
              )}
            </Fragment>
          )
        })}
      </div>
      {pendingImage && (
        <div className="attach-preview">
          <img src={pendingImage.previewUrl} alt="첨부할 이미지 미리보기" />
          <span className="attach-name">{pendingImage.name}</span>
          <span className="attach-note">사진은 표정·분위기로 감정만 분석하고 바로 버려요(저장·내용 인식 안 함).</span>
          <button className="attach-remove" type="button" title="첨부 취소" onClick={clearImage}>✕</button>
        </div>
      )}
      <div className="console-input-row">
        <input ref={fileRef} type="file" accept={IMAGE_ACCEPT} hidden onChange={pickFile} />
        <button
          className="console-send console-attach"
          type="button"
          disabled={sending}
          title="사진 첨부 — 감정 분석에만 쓰고 버려요 (붙여넣기·끌어다 놓기도 가능)"
          onClick={() => fileRef.current && fileRef.current.click()}
        >
          📎
        </button>
        <input
          type="text"
          className="console-input"
          placeholder="메시지를 입력하세요..."
          autoComplete="off"
          maxLength={2000}
          value={inputValue}
          disabled={sending}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
        />
        <select
          className="console-check-source"
          value={checkSource}
          disabled={sending}
          title="교차 검증에 쓸 검색 근거"
          onChange={(e) => setCheckSource(e.target.value)}
        >
          <option value="both">네이버+구글</option>
          <option value="naver">네이버</option>
          <option value="google">구글</option>
        </select>
        <button
          className="console-send console-check"
          type="button"
          disabled={sending}
          title="Lumi의 직전 답변을 선택한 검색 근거로 교차 검증"
          onClick={crossCheck}
        >
          CROSS-CHECK
        </button>
        <button className="console-send" type="button" disabled={sending} onClick={handleSend}>SEND</button>
      </div>
    </section>
  )
}
