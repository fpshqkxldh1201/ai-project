import { useState } from 'react'
import { useAuth } from '../context/AuthContext.jsx'

// 8081의 login.mustache/register.mustache와 같은 사이언/앰버 HUD 테마를 React 쪽에서도
// 그대로 재현한 로그인+회원가입 화면. 별도 CSS 파일에 의존하지 않도록 스타일을 자체 포함한다.
export default function LoginView() {
  const { login, register, loginWithGoogle, notice, clearNotice } = useAuth()
  const [mode, setMode] = useState('login') // 'login' | 'register'
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [message, setMessage] = useState(null) // { text, ok }
  const [submitting, setSubmitting] = useState(false)

  // 자동 로그아웃/세션 만료 안내는 한 번 보여주고, 다음 조작부터는 지운다.
  const shownNotice = notice && !message ? { text: notice, ok: false } : message

  function resetFields() {
    setPassword('')
    setConfirmPassword('')
  }

  async function handleSubmit(e) {
    e.preventDefault()
    setMessage(null)
    clearNotice()

    if (mode === 'register' && password !== confirmPassword) {
      setMessage({ text: '비밀번호가 일치하지 않습니다.', ok: false })
      return
    }

    setSubmitting(true)
    try {
      if (mode === 'login') {
        await login(username, password)
        // 성공하면 AuthProvider의 status가 바뀌면서 App.jsx가 알아서 메인 화면으로 전환한다
      } else {
        await register(username, password, confirmPassword)
        setMessage({ text: '가입이 완료되었습니다. 로그인해주세요.', ok: true })
        setMode('login')
        resetFields()
      }
    } catch (err) {
      setMessage({ text: err.message || '처리에 실패했습니다.', ok: false })
    } finally {
      setSubmitting(false)
    }
  }

  function switchMode(next) {
    setMode(next)
    setMessage(null)
    resetFields()
  }

  return (
    <div className="lumi-auth-page">
      <style>{`
        .lumi-auth-page {
          --cyan: #3FE0FF; --cyan-glow: rgba(63,224,255,0.4); --amber: #FFA94D; --bg: #02060c;
          background-color: var(--bg); color: var(--cyan); font-family: 'Rajdhani', sans-serif;
          min-height: 100vh; display: flex; align-items: center; justify-content: center;
          background-image:
            radial-gradient(circle at 50% 30%, rgba(63,224,255,0.08) 0%, transparent 60%),
            linear-gradient(rgba(63,224,255,0.035) 1px, transparent 1px),
            linear-gradient(90deg, rgba(63,224,255,0.035) 1px, transparent 1px);
          background-size: 100% 100%, 32px 32px, 32px 32px;
        }
        .lumi-auth-card {
          width: 340px; background: rgba(4,12,24,0.78); border: 1px solid rgba(63,224,255,0.28);
          padding: 34px 30px; backdrop-filter: blur(10px);
          box-shadow: inset 0 0 18px rgba(63,224,255,0.05), 0 0 24px rgba(0,0,0,0.6);
          clip-path: polygon(0 0, calc(100% - 16px) 0, 100% 16px, 100% 100%, 16px 100%, 0 calc(100% - 16px));
          position: relative;
        }
        .lumi-auth-card::before { content: ''; position: absolute; top: 0; left: 0; width: 3px; height: 100%; background: var(--cyan); box-shadow: 0 0 8px var(--cyan); }
        .lumi-auth-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 26px; }
        .lumi-auth-logo { width: 12px; height: 12px; background: var(--cyan); box-shadow: 0 0 10px var(--cyan); transform: rotate(45deg); }
        .lumi-auth-title { font-family: 'Orbitron', sans-serif; font-weight: 900; font-size: 1.3rem; letter-spacing: 5px; text-shadow: 0 0 12px var(--cyan-glow); }
        .lumi-auth-card h1 { font-size: 0.85rem; font-weight: 700; letter-spacing: 1px; color: #E4F7FF; margin: 0 0 20px; }
        .lumi-auth-card label { display: block; font-size: 0.68rem; font-weight: 600; letter-spacing: 1px; color: #4A6472; margin-bottom: 6px; }
        .lumi-auth-card input {
          width: 100%; background: rgba(0,0,0,0.3); border: 1px solid rgba(63,224,255,0.25); color: var(--cyan);
          font-family: 'Rajdhani', sans-serif; font-size: 0.9rem; font-weight: 600; padding: 10px 12px; margin-bottom: 16px; box-sizing: border-box;
        }
        .lumi-auth-card input:focus { outline: none; border-color: var(--cyan); box-shadow: 0 0 8px rgba(63,224,255,0.3); }
        .lumi-auth-card button, .lumi-auth-card .lumi-auth-btn {
          width: 100%; padding: 11px; font-family: 'Rajdhani', sans-serif; font-weight: 700; letter-spacing: 1px;
          cursor: pointer; border: none; font-size: 0.85rem; display: block; text-align: center; text-decoration: none; box-sizing: border-box;
        }
        .lumi-auth-primary { background: var(--cyan); color: #02060c; box-shadow: 0 0 12px var(--cyan-glow); margin-bottom: 14px; }
        .lumi-auth-primary:disabled { opacity: 0.5; cursor: not-allowed; }
        .lumi-auth-divider { display: flex; align-items: center; gap: 10px; margin: 16px 0; font-size: 0.62rem; color: #3B4F5A; letter-spacing: 1px; }
        .lumi-auth-divider::before, .lumi-auth-divider::after { content: ''; flex: 1; height: 1px; background: rgba(63,224,255,0.2); }
        .lumi-auth-google { background: transparent; border: 1px solid rgba(255,169,77,0.4); color: var(--amber); }
        .lumi-auth-google:hover { background: rgba(255,169,77,0.08); }
        .lumi-auth-msg { font-size: 0.74rem; padding: 9px 10px; margin-bottom: 16px; border-left: 2px solid var(--amber); color: var(--amber); background: rgba(255,169,77,0.08); }
        .lumi-auth-msg.ok { border-left-color: var(--cyan); color: var(--cyan); background: rgba(63,224,255,0.08); }
        .lumi-auth-hint { font-size: 0.64rem; color: #4A6472; margin: -10px 0 16px; }
        .lumi-auth-footer { text-align: center; margin-top: 18px; font-size: 0.74rem; color: #4A6472; }
        .lumi-auth-footer a { color: var(--cyan); text-decoration: none; cursor: pointer; }
      `}</style>

      <div className="lumi-auth-card">
        <div className="lumi-auth-brand">
          <div className="lumi-auth-logo"></div>
          <div className="lumi-auth-title">LUMI</div>
        </div>
        <h1>{mode === 'login' ? '계정으로 로그인' : '새 계정 만들기'}</h1>

        {shownNotice && <div className={`lumi-auth-msg${shownNotice.ok ? ' ok' : ''}`}>{shownNotice.text}</div>}

        <form onSubmit={handleSubmit}>
          <label htmlFor="lumi-username">아이디</label>
          <input
            id="lumi-username"
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
            autoFocus
          />
          <label htmlFor="lumi-password">비밀번호</label>
          <input
            id="lumi-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            minLength={mode === 'register' ? 8 : undefined}
            required
          />
          {mode === 'register' && (
            <div className="lumi-auth-hint">8자 이상, 영문·숫자·특수문자 중 2종류 이상 (흔한 비밀번호·아이디 포함은 불가)</div>
          )}
          {mode === 'register' && (
            <>
              <label htmlFor="lumi-confirm">비밀번호 확인</label>
              <input
                id="lumi-confirm"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
                minLength={8}
                required
              />
            </>
          )}
          <button type="submit" className="lumi-auth-primary" disabled={submitting}>
            {mode === 'login' ? '로그인' : '가입하기'}
          </button>
        </form>

        {mode === 'login' && (
          <>
            <div className="lumi-auth-divider">또는</div>
            <a className="lumi-auth-btn lumi-auth-google" onClick={loginWithGoogle} href="#google-login">
              Google로 로그인
            </a>
          </>
        )}

        <div className="lumi-auth-footer">
          {mode === 'login' ? (
            <>계정이 없으신가요? <a onClick={() => switchMode('register')}>회원가입</a></>
          ) : (
            <>이미 계정이 있으신가요? <a onClick={() => switchMode('login')}>로그인</a></>
          )}
        </div>
      </div>
    </div>
  )
}
