import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
      // 로그아웃은 /api 밖에 있는 Spring Security 기본 엔드포인트라 별도로 프록시해야
      // 같은 출처(same-origin) fetch로 호출돼서 8081의 세션 쿠키가 같이 전달된다.
      '/logout': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
})
