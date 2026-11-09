import { useEffect } from 'react'
import { ToastProvider } from './context/ToastContext.jsx'
import { LumiProvider, useLumi } from './context/LumiContext.jsx'
import { AuthProvider, useAuth } from './context/AuthContext.jsx'
import { useToast } from './context/ToastContext.jsx'
import useIdleLogout from './hooks/useIdleLogout.js'
import ToastContainer from './components/ToastContainer.jsx'
import LoginView from './components/LoginView.jsx'
import Header from './components/Header.jsx'
import LeftPanel from './components/LeftPanel.jsx'
import CenterCore from './components/CenterCore.jsx'
import RightPanel from './components/RightPanel.jsx'
import ConsoleSection from './components/ConsoleSection.jsx'
import SettingsModal from './components/SettingsModal.jsx'
import Footer from './components/Footer.jsx'

function LumiPage() {
  const { ttsPlayerRef } = useLumi()
  const { displayName, logout, expireSession } = useAuth()
  const { showToast } = useToast()

  // 자리 비움 자동 로그아웃: 15분 동안 아무 조작이 없으면 로그아웃(1분 전에 안내).
  useIdleLogout({
    onWarn: () => showToast('error', '자동 로그아웃 예정', '1분 안에 아무 조작이 없으면 보안을 위해 로그아웃됩니다.', 8000),
    onTimeout: () => logout('오래 사용하지 않아 자동으로 로그아웃되었습니다.'),
  })

  // 서버가 세션을 폐기한 경우(알림 확인 등이 401 SESSION_EXPIRED를 받음) 로그인 화면으로 돌아간다.
  useEffect(() => {
    const handler = () => expireSession('세션이 만료되어 로그아웃되었습니다. 다시 로그인해주세요.')
    window.addEventListener('lumi:session-expired', handler)
    return () => window.removeEventListener('lumi:session-expired', handler)
  }, [expireSession])

  return (
    <>
      <div className="bg-grid"></div>
      {/* 기존 Header.jsx는 건드리지 않고, 로그인 사용자 표시+로그아웃만 화면 우상단에
          별도로 얹는다. 8081의 static/index.html 헤더와 같은 정보를 보여준다. */}
      <div
        style={{
          position: 'fixed', top: 14, right: 18, zIndex: 50,
          display: 'flex', alignItems: 'center', gap: 10,
          fontFamily: "'Rajdhani', sans-serif", fontSize: '0.8rem', fontWeight: 600,
        }}
      >
        <span style={{ color: '#A9C7D3' }}>{displayName || '사용자'}</span>
        <button
          onClick={logout}
          style={{
            background: 'none', border: 'none', color: '#3FE0FF', fontFamily: 'inherit',
            fontSize: '0.8rem', fontWeight: 600, letterSpacing: '0.5px', cursor: 'pointer',
            padding: 0, textDecoration: 'underline',
          }}
        >
          로그아웃
        </button>
      </div>
      <Header />
      <main>
        <LeftPanel />
        <CenterCore />
        <RightPanel />
      </main>
      <ConsoleSection />
      <SettingsModal />
      <audio ref={ttsPlayerRef} style={{ display: 'none' }} />
      <ToastContainer />
      <Footer />
    </>
  )
}

function AuthGate() {
  const { status } = useAuth()

  if (status === 'loading') {
    return (
      <div
        style={{
          minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
          background: '#02060c', color: '#3FE0FF', fontFamily: "'Rajdhani', sans-serif",
          letterSpacing: '2px', fontSize: '0.85rem',
        }}
      >
        LUMI 로딩 중...
      </div>
    )
  }

  if (status === 'anonymous') {
    return <LoginView />
  }

  return (
    <ToastProvider>
      <LumiProvider>
        <LumiPage />
      </LumiProvider>
    </ToastProvider>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  )
}
