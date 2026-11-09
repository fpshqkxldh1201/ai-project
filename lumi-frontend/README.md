# Lumi Frontend (React + Vite)

Lumi(자비스st)의 정적 HTML/JS 프론트엔드(`static/index.html`)를 React로 포팅한 버전입니다.
Spring Boot 백엔드(포트 8081)는 그대로 두고, 이 프로�트를 별도로(포트 5173) 띄워서 씁니다.

## 실행

```bash
npm install
npm run dev
```

`npm run dev`로 뜨는 개발 서버는 `/api/**` 요청을 `http://localhost:8081`로 프록시합니다
(`vite.config.js` 참고). Spring Boot 애플리케이션을 먼저 실행해두세요.

## 배포용 빌드

```bash
npm run build
```

`dist/`에 정적 파일이 생성됩니다. Spring Boot의 `static/` 폴더에 그대로 복사해 넣으면
8081 하나로 서빙할 수도 있습니다(기존 바닐라 JS 버전과 택일).

## 구조

- `src/context/ToastContext.jsx` — 토스트 알림 (원본 `toast.js`)
- `src/context/LumiContext.jsx` — 설정·마이크(STT)·TTS·채팅·일정/사용량 대시보드 전체 상태
  (원본 `voice.js` + `chat.js` + `dashboard.js`를 하나의 컨텍스트로 통합)
- `src/three/neuronScene.js` — Three.js 뉴런 플렉서스 코어 (원본 `neuron-scene.js`)
- `src/hooks/useClock.js` — 디지털 시계
- `src/components/` — Header / LeftPanel(기능 목록+사용량 차트) / CenterCore(3D 코어) /
  RightPanel(일정) / ConsoleSection(채팅) / SettingsModal / Footer(마이크) / ToastContainer

## 아직 원본과 동일하게 남겨둔 것

- 페이지가 하나뿐이라 라우팅(react-router)은 도입하지 않았습니다.
- "WEEKLY DENSITY" 차트는 원본처럼 정적 목업 데이터입니다(백엔드 연동 없음).
