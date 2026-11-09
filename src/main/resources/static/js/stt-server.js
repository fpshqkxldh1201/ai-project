(function () {
'use strict';
// ── 서버 음성 인식(STT) 엔진 ──────────────────────────────────────────────
// 브라우저 SpeechRecognition과 같은 모양(lang/onresult/onerror/onend/start/stop/abort)의 객체를
// 만들어서, 기존 음성 코드를 거의 건드리지 않고 엔진만 바꿀 수 있게 한다.
// 마이크 녹음(MediaRecorder) + 침묵 감지(음량 기준)로 한 문장이 끝나면 /api/stt(OpenAI 전사)로 보내고,
// 받은 글자를 onresult로 돌려준다. 음성 파일은 서버에 저장되지 않는다.
const STT_ENGINE_KEY = 'lumi.sttEngine'; // 'browser'(기본) | 'server'
const STT_MAX_BYTES = 880 * 1024;

const sttState = { available: null }; // null=아직 모름, true/false=서버 상태

function sttCsrf() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}

function sttGetEngine() {
  try { return localStorage.getItem(STT_ENGINE_KEY) === 'server' ? 'server' : 'browser'; } catch { return 'browser'; }
}

function sttSetEngine(value) {
  try { localStorage.setItem(STT_ENGINE_KEY, value === 'server' ? 'server' : 'browser'); } catch { /* 저장 불가 환경이면 무시 */ }
}

async function sttRefreshStatus() {
  try {
    const res = await fetch('/api/stt/status', { credentials: 'same-origin' });
    if (!res.ok) { sttState.available = false; return false; }
    const data = await res.json();
    sttState.available = !!data.available;
  } catch {
    sttState.available = false;
  }
  return sttState.available;
}

function sttPickMime() {
  if (typeof MediaRecorder === 'undefined') return null;
  const list = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];
  for (const m of list) {
    try { if (MediaRecorder.isTypeSupported(m)) return m; } catch { /* 다음 후보 */ }
  }
  return '';
}

function sttSupported() {
  return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && typeof MediaRecorder !== 'undefined');
}

class ServerSpeechRecognition {
  constructor() {
    this.lang = 'ko-KR';
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.onresult = null;
    this.onerror = null;
    this.onend = null;
    // 조정 가능한 값(ms)
    this.silenceMs = 1300;   // 말한 뒤 이만큼 조용하면 한 문장이 끝난 것으로 본다
    this.noSpeechMs = 8000;  // 아무 말도 없으면 포기
    this.maxMs = 25000;      // 한 번에 너무 길게 녹음하지 않는다
    this._active = false;
    this._aborted = false;
    this._ended = false;
  }

  start() {
    if (this._active) throw new Error('이미 듣는 중입니다.');
    this._active = true;
    this._aborted = false;
    this._ended = false;
    if (!sttSupported()) {
      setTimeout(() => this._fail('audio-capture'), 0);
      return;
    }
    navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })
      .then((stream) => {
        if (this._aborted) { stream.getTracks().forEach((t) => t.stop()); this._finishEnd(); return; }
        this._begin(stream);
      })
      .catch((err) => {
        this._fail(err && (err.name === 'NotAllowedError' || err.name === 'SecurityError') ? 'not-allowed' : 'audio-capture');
      });
  }

  _begin(stream) {
    this._stream = stream;
    this._chunks = [];
    const Ctx = window.AudioContext || window.webkitAudioContext;
    this._ctx = Ctx ? new Ctx() : null;
    if (this._ctx && this._ctx.state === 'suspended') this._ctx.resume().catch(() => {});
    if (this._ctx) {
      const src = this._ctx.createMediaStreamSource(stream);
      this._analyser = this._ctx.createAnalyser();
      this._analyser.fftSize = 1024;
      src.connect(this._analyser);
      this._buf = new Uint8Array(this._analyser.fftSize);
    }
    const mime = sttPickMime();
    try {
      this._rec = new MediaRecorder(stream, mime ? { mimeType: mime, audioBitsPerSecond: 32000 } : { audioBitsPerSecond: 32000 });
    } catch {
      this._cleanup();
      this._fail('audio-capture');
      return;
    }
    this._mime = this._rec.mimeType || mime || 'audio/webm';
    this._rec.ondataavailable = (e) => { if (e.data && e.data.size > 0) this._chunks.push(e.data); };
    this._rec.onstop = () => this._onRecorded();
    this._rec.start(250);

    this._t0 = performance.now();
    this._lastVoice = this._t0;
    this._voicedMs = 0;
    this._speech = false;
    this._noise = [];
    this._thr = 0.02;
    this._timer = setInterval(() => this._tick(), 50);
  }

  _rms() {
    if (!this._analyser) return 0;
    this._analyser.getByteTimeDomainData(this._buf);
    let sum = 0;
    for (let i = 0; i < this._buf.length; i++) {
      const v = (this._buf[i] - 128) / 128;
      sum += v * v;
    }
    return Math.sqrt(sum / this._buf.length);
  }

  _tick() {
    const now = performance.now();
    const elapsed = now - this._t0;
    const level = this._rms();
    if (elapsed < 400) {
      this._noise.push(level); // 처음 0.4초는 주변 소음 기준선으로만 쓴다
      if (this._noise.length) {
        const sorted = [...this._noise].sort((a, b) => a - b);
        const median = sorted[Math.floor(sorted.length / 2)];
        this._thr = Math.max(0.02, median * 3);
      }
    } else if (level > this._thr) {
      this._voicedMs += 50;
      this._lastVoice = now;
      if (this._voicedMs >= 200) this._speech = true;
    }
    if (this._speech && now - this._lastVoice >= this.silenceMs) { this._stopRecording(); return; }
    if (!this._speech && elapsed >= this.noSpeechMs) { this._stopRecording(); return; }
    if (elapsed >= this.maxMs) { this._stopRecording(); }
  }

  _stopRecording() {
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    if (this._rec && this._rec.state !== 'inactive') {
      try { this._rec.stop(); } catch { this._onRecorded(); }
    } else {
      this._onRecorded();
    }
  }

  // 사용자가 마이크를 눌러 멈춘 경우: 지금까지 말한 게 있으면 그대로 인식한다(브라우저 stop()과 같은 동작).
  stop() {
    if (!this._active || this._ended) return;
    if (this._rec) this._stopRecording();
    else this._aborted = true; // 아직 마이크 권한 대기 중이면 취소
  }

  abort() {
    this._aborted = true;
    if (this._rec) this._stopRecording();
  }

  _cleanup() {
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    if (this._stream) { this._stream.getTracks().forEach((t) => t.stop()); this._stream = null; }
    if (this._ctx) { this._ctx.close().catch(() => {}); this._ctx = null; }
  }

  async _onRecorded() {
    const speech = this._speech;
    const chunks = this._chunks || [];
    const mime = this._mime;
    this._cleanup();
    this._rec = null;
    if (this._aborted) { this._finishEnd(); return; }
    if (!speech || chunks.length === 0) {
      this._emitError('no-speech');
      this._finishEnd();
      return;
    }
    const blob = new Blob(chunks, { type: mime });
    if (blob.size > STT_MAX_BYTES) {
      this._emitError('audio-too-long');
      this._finishEnd();
      return;
    }
    const form = new FormData();
    const ext = /mp4/.test(mime) ? 'mp4' : /ogg/.test(mime) ? 'ogg' : 'webm';
    form.append('file', blob, 'speech.' + ext);
    form.append('lang', String(this.lang || 'ko-KR').split('-')[0]);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 25000);
    try {
      const res = await fetch('/api/stt', {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': sttCsrf() },
        body: form,
        credentials: 'same-origin',
        signal: ctrl.signal,
      });
      if (!res.ok) {
        if (res.status === 503) sttState.available = false;
        this._emitError(res.status === 429 ? 'rate-limited' : res.status === 503 ? 'service-not-allowed' : 'network');
      } else {
        const data = await res.json();
        const text = String(data.text || '').trim();
        if (text && !this._aborted) {
          if (typeof this.onresult === 'function') {
            this.onresult({ resultIndex: 0, results: [[{ transcript: text, confidence: 1 }]] });
          }
        } else {
          this._emitError('no-speech');
        }
      }
    } catch {
      this._emitError('network');
    } finally {
      clearTimeout(timer);
      this._finishEnd();
    }
  }

  _emitError(code) {
    if (typeof this.onerror === 'function') this.onerror({ error: code });
  }

  _fail(code) {
    this._emitError(code);
    this._finishEnd();
  }

  _finishEnd() {
    if (this._ended) return;
    this._ended = true;
    this._active = false;
    if (typeof this.onend === 'function') this.onend();
  }
}

// 설정에서 서버 엔진을 골랐고 서버가 준비된 경우에만 서버 엔진을 쓰고, 아니면 브라우저 기본 엔진을 그대로 쓴다.
function sttPickRecognition(Native) {
  if (sttGetEngine() === 'server' && sttState.available !== false && sttSupported()) {
    return ServerSpeechRecognition;
  }
  return Native;
}

function sttIsServerActive() {
  return sttGetEngine() === 'server' && sttState.available !== false && sttSupported();
}

if (sttGetEngine() === 'server') sttRefreshStatus();
window.LumiServerStt = {
  getEngine: sttGetEngine, setEngine: (v) => { sttSetEngine(v); if (v === 'server') sttRefreshStatus(); },
  refreshStatus: sttRefreshStatus, pick: sttPickRecognition, isServerActive: sttIsServerActive,
  supported: sttSupported, Recognition: ServerSpeechRecognition, state: sttState,
};
})();
