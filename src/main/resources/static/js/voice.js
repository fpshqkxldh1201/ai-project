// 음성 입출력 — 마이크 상태 UI, STT(Web Speech API), 설정창(⚙️: 입력/답변 언어, 음성 성별, TTS 토글),
// TTS(Fish Audio 우선 + 브라우저 내장 speechSynthesis 자동 폴백)

  // 마이크 상태
  const wave = document.getElementById('eqWave');
  const voiceState = document.getElementById('voiceState');
  const centerState = document.getElementById('centerState');
  const coreSvg = document.getElementById('coreSvg');
  const coreStage = document.getElementById('coreStage');
  const pulse1 = document.getElementById('pulse1');
  const pulse2 = document.getElementById('pulse2');
  let recognition = null;
  let recognizing = false;

  wave.addEventListener('click', () => {
    if (recognizing) {
      stopListening();
    } else {
      startListening();
    }
  });

  function sttLangCode(code) {
    switch (code) {
      case 'en': return 'en-US';
      case 'zh': return 'zh-CN';
      case 'ja': return 'ja-JP';
      case 'es': return 'es-ES';
      default: return 'ko-KR';
    }
  }

  function isSpeaking() {
    const ttsPlayerEl = document.getElementById('ttsPlayer');
    const audioPlaying = ttsPlayerEl && !ttsPlayerEl.paused;
    const synthSpeaking = window.speechSynthesis && window.speechSynthesis.speaking;
    return !!(audioPlaying || synthSpeaking);
  }

  // 브라우저 내장 Web Speech API로 음성 인식(STT)을 수행한다. Chrome/Edge 지원,
  // Firefox는 미지원이며 이 경우 에러 토스트로 안내한다. 언어는 TTS 언어 선택과 공유한다.
  function startListening() {
    if (isSpeaking()) {
      showToast('error', 'MIC BUSY', 'Lumi가 말하는 중에는 음성 인식을 시작할 수 없습니다.');
      return;
    }
    const SpeechRecognitionImpl = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognitionImpl) {
      showToast('error', 'STT UNAVAILABLE', '이 브라우저는 음성 인식을 지원하지 않습니다 (Chrome 권장).');
      return;
    }

    recognition = new SpeechRecognitionImpl();
    recognition.lang = sttLangCode(inputLangSelect.value);
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event) => {
      let transcript = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        transcript += event.results[i][0].transcript;
      }
      consoleInput.value = transcript;
    };

    recognition.onerror = (event) => {
      console.error('STT 오류:', event.error);
      showToast('error', 'STT ERROR', '음성 인식 오류: ' + event.error);
    };

    // 침묵이 감지되면 브라우저가 자동으로 인식을 종료한다 — 이때 인식된 문장을 바로 전송한다.
    recognition.onend = () => {
      recognizing = false;
      setActiveVisual(false, 'LISTENING');
      if (consoleInput.value.trim()) {
        sendMessage();
      }
    };

    try {
      recognition.start();
      recognizing = true;
      setActiveVisual(true, 'LISTENING');
    } catch (err) {
      console.error('STT 시작 실패:', err);
      showToast('error', 'STT START FAILED', '음성 인식을 시작하지 못했습니다: ' + err.message);
    }
  }

  function stopListening() {
    if (recognition) recognition.stop();
  }

  function setActiveVisual(active, label) {
    listening = active;
    wave.classList.toggle('listening', active);
    voiceState.textContent = active ? (label + '...') : 'STANDBY';
    voiceState.classList.toggle('listening', active);
    centerState.textContent = active ? label : 'STANDBY';
    centerState.classList.toggle('listening', active);
    coreSvg.classList.toggle('listening', active);
    coreStage.classList.toggle('listening', active);
    pulse1.classList.toggle('listening', active);
    pulse2.classList.toggle('listening', active);
  }

  const settingsBtn = document.getElementById('settingsBtn');
  const settingsModal = document.getElementById('settingsModal');
  const settingsClose = document.getElementById('settingsClose');
  const inputLangSelect = document.getElementById('inputLangSelect');
  const responseLangSelect = document.getElementById('responseLangSelect');
  const voiceGenderSelect = document.getElementById('voiceGenderSelect');
  const ttsToggle = document.getElementById('ttsToggle');
  const ttsPlayer = document.getElementById('ttsPlayer');
  let ttsEnabled = true;

  settingsBtn.addEventListener('click', () => settingsModal.classList.add('open'));
  settingsClose.addEventListener('click', () => settingsModal.classList.remove('open'));
  settingsModal.addEventListener('click', (e) => {
    if (e.target === settingsModal) settingsModal.classList.remove('open');
  });

  // AI가 실제로 어떤 언어로 답해야 하는지: '자동'이면 사용자가 말한/입력한 언어를 그대로 따르고,
  // 명시적으로 고르면 그 언어로 고정한다. /api/chat에 힌트로 실어 보낸다.
  function getResponseLanguage() {
    const setting = responseLangSelect.value;
    return setting === 'auto' ? null : setting;
  }

  // TTS 재생에 쓸 실제 언어 — 답변 언어가 고정돼 있으면 그 언어, 자동이면 입력 언어를 따른다.
  function getEffectiveTtsLang() {
    const setting = responseLangSelect.value;
    return setting === 'auto' ? inputLangSelect.value : setting;
  }

  let cachedVoices = [];
  function refreshVoiceCache() {
    if (window.speechSynthesis) cachedVoices = window.speechSynthesis.getVoices();
  }
  if (window.speechSynthesis) {
    refreshVoiceCache();
    window.speechSynthesis.onvoiceschanged = refreshVoiceCache;
  }

  // OS/브라우저에 설치된 음성 중, 언어가 맞고 이름에서 성별을 유추할 수 있는 걸 고른다.
  // 완벽하진 않다 — 음성 목록이 플랫폼마다 달라서, 못 찾으면 그 언어의 첫 번째 음성을 쓴다.
  function pickBrowserVoice(langCode, gender) {
    if (!cachedVoices.length) refreshVoiceCache();
    const prefix = langCode.split('-')[0].toLowerCase();
    const candidates = cachedVoices.filter(v => v.lang && v.lang.toLowerCase().startsWith(prefix));
    if (!candidates.length) return null;
    const femaleHints = ['female', 'yuna', 'heami', 'nara', '여성', 'samantha', 'zira', 'susan', 'aria'];
    const maleHints = ['male', 'injoon', 'daniel', 'david', '남성', 'fred', 'alex', 'guy'];
    const hints = gender === 'male' ? maleHints : femaleHints;
    const match = candidates.find(v => hints.some(h => v.name.toLowerCase().includes(h)));
    return match || candidates[0];
  }

  ttsToggle.addEventListener('click', () => {
    ttsEnabled = !ttsEnabled;
    ttsToggle.textContent = ttsEnabled ? '🔊' : '🔇';
    ttsToggle.classList.toggle('muted', !ttsEnabled);
    if (!ttsEnabled) {
      ttsPlayer.pause();
      window.speechSynthesis.cancel();
    }
  });

  function resetSpeakingVisual() {
    setActiveVisual(false, 'LISTENING');
  }
  ttsPlayer.addEventListener('ended', resetSpeakingVisual);
  ttsPlayer.addEventListener('pause', resetSpeakingVisual);

  // 브라우저 내장 음성합성(Web Speech API) — Fish Audio가 실패하거나 미설정일 때의
  // 폴백 경로. 서버 호출도, 키도 필요 없어 항상 동작한다.
  function speakBrowser(text) {
    if (!window.speechSynthesis) {
      console.error('이 브라우저는 음성 합성을 지원하지 않습니다.');
      return;
    }
    window.speechSynthesis.cancel();
    const langCode = sttLangCode(getEffectiveTtsLang());
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = langCode;
    const voice = pickBrowserVoice(langCode, voiceGenderSelect.value);
    if (voice) utterance.voice = voice;
    utterance.onstart = () => setActiveVisual(true, 'SPEAKING');
    utterance.onend = resetSpeakingVisual;
    utterance.onerror = (event) => {
      console.error('TTS 재생 실패:', event.error);
      resetSpeakingVisual();
    };
    window.speechSynthesis.speak(utterance);
  }

  // Lumi의 답변을 우선 Fish Audio로 합성해서 재생하고, 키가 없거나 호출이 실패하면
  // 조용히 브라우저 내장 음성합성으로 넘어간다 — 어느 쪽이든 대화 자체는 끊기지 않는다.
  async function speak(text) {
    if (!ttsEnabled || !text) return;
    try {
      const res = await fetch('/api/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text,
          language: getEffectiveTtsLang(),
          gender: voiceGenderSelect.value
        })
      });
      if (res.ok) {
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        ttsPlayer.src = url;
        setActiveVisual(true, 'SPEAKING');
        await ttsPlayer.play();
        return;
      }
    } catch (err) {
      console.error('Fish Audio TTS 실패, 브라우저 음성으로 대체합니다:', err);
    }
    speakBrowser(text);
  }
