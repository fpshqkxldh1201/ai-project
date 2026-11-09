import { createContext, useCallback, useContext, useEffect, useState } from 'react'

// 로그인 상태(누가 로그인했는지)를 앱 전체에서 공유하기 위한 Context.
// 8081(Spring Boot) 세션 쿠키를 그대로 쓰고, /api/auth/* 를 통해서만 로그인 여부를 확인한다.
const AuthContext = createContext(null)

const GOOGLE_LOGIN_URL = 'http://localhost:8081/oauth2/authorization/google'
const RETURN_TO_COOKIE = 'lumi_return_to'

// CSRF(쿠키 기반 더블 서브밋): 백엔드(SecurityConfig)가 응답마다 심어주는 XSRF-TOKEN
// 쿠키 값을 그대로 읽어 X-XSRF-TOKEN 헤더로 되돌려보낸다. Vite 프록시가 /api, /logout을
// 8081로 그대로 넘겨주므로 이 쿠키도 세션 쿠키처럼 5173에서 바로 읽힌다. 앱이 처음 뜨면서
// 호출하는 GET /api/auth/me 응답에서 이미 쿠키가 세팅되므로, 그 다음에 일어나는 로그인/
// 가입/로그아웃 POST 시점엔 항상 최신 토큰을 읽을 수 있다.
function getCsrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

export function AuthProvider({ children }) {
  // status: 'loading' | 'authenticated' | 'anonymous'
  const [status, setStatus] = useState('loading')
  const [displayName, setDisplayName] = useState(null)
  const [provider, setProvider] = useState(null)
  // 자동 로그아웃·세션 만료로 로그인 화면에 돌아왔을 때 보여줄 안내 문구
  const [notice, setNotice] = useState(null)

  const refreshAuth = useCallback(async () => {
    try {
      const res = await fetch('/api/auth/me')
      const me = await res.json()
      if (me.authenticated) {
        setStatus('authenticated')
        setDisplayName(me.displayName)
        setProvider(me.provider)
      } else {
        setStatus('anonymous')
        setDisplayName(null)
        setProvider(null)
      }
    } catch {
      // 백엔드(8081)가 꺼져 있는 등 네트워크 에러면 로그인 화면을 보여주는 쪽이 안전하다
      setStatus('anonymous')
    }
  }, [])

  useEffect(() => {
    refreshAuth()
  }, [refreshAuth])

  async function login(username, password) {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
      body: JSON.stringify({ username, password }),
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      throw new Error(body.error || '로그인에 실패했습니다.')
    }
    await refreshAuth()
  }

  async function register(username, password, confirmPassword) {
    const res = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCsrfToken() },
      body: JSON.stringify({ username, password, confirmPassword }),
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      throw new Error(body.error || '가입에 실패했습니다.')
    }
  }

  function loginWithGoogle() {
    // 구글 로그인 후 8081이 아니라 이 React 개발 서버(5173)로 돌아오도록 표시해둔다.
    // 쿠키는 포트와 무관하게 "localhost"를 기준으로 공유되므로, 8081의 OAuth 성공
    // 핸들러(OAuth2ReturnToSuccessHandler)가 이 쿠키를 읽어서 돌아갈 곳을 정한다.
    document.cookie = `${RETURN_TO_COOKIE}=5173; path=/; max-age=120`
    window.location.href = GOOGLE_LOGIN_URL
  }

  async function logout(reason) {
    setNotice(typeof reason === 'string' ? reason : null)
    try {
      await fetch('/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': getCsrfToken() } })
    } catch {
      // 네트워크 에러여도 화면상으로는 로그아웃 처리한다
    }
    setStatus('anonymous')
    setDisplayName(null)
    setProvider(null)
  }

  // 서버가 세션을 폐기했을 때(유휴 만료·다른 기기 사용 감지) 서버 호출 없이 화면만 로그인으로 되돌린다.
  const expireSession = useCallback((message) => {
    setNotice(message)
    setStatus('anonymous')
    setDisplayName(null)
    setProvider(null)
  }, [])

  const clearNotice = useCallback(() => setNotice(null), [])

  const value = {
    status, displayName, provider, notice, clearNotice,
    login, register, loginWithGoogle, logout, refreshAuth, expireSession,
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth는 AuthProvider 안에서만 사용할 수 있습니다.')
  }
  return ctx
}
