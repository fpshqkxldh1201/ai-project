// LUMI CONSOLE 채팅 — 메시지 전송/렌더링, 감정 배지, /api/chat 호출, 페이지 로드 시 인사 브리핑

  const consoleLog = document.getElementById('consoleLog');
  const consoleInput = document.getElementById('consoleInput');
  const consoleSend = document.getElementById('consoleSend');
  const consoleTag = document.getElementById('consoleTag');


  const EMOTION_LABEL_KO = {
    happy: '행복', energetic: '활기참', calm: '차분함', sad: '슬픔',
    angry: '화남', dreamy: '몽환적', romantic: '설렘', melancholy: '우울'
  };

  function appendLog(sender, text) {
    const row = document.createElement('div');
    row.className = 'log-row log-' + sender;
    const tag = document.createElement('span');
    tag.className = 'log-tag';
    tag.textContent = sender === 'user' ? 'YOU' : 'LUMI';
    const body = document.createElement('span');
    body.className = 'log-text';
    body.textContent = text;
    row.appendChild(tag);
    row.appendChild(body);
    consoleLog.appendChild(row);
    consoleLog.scrollTop = consoleLog.scrollHeight;
    return row;
  }

  // 사용자가 보낸 메시지 옆에, 감정 분석 모델로 분석된 그 메시지의 감정을 작은 배지로 붙인다.
  function attachEmotionBadge(row, emotion) {
    if (!row || !emotion || !emotion.primaryEmotion) return;
    const badge = document.createElement('span');
    badge.className = 'log-emotion';
    let label = EMOTION_LABEL_KO[emotion.primaryEmotion] || emotion.primaryEmotion;
    if (typeof emotion.confidence === 'number') {
      label += ' ' + Math.round(emotion.confidence * 100) + '%';
    }
    badge.textContent = label;
    row.appendChild(badge);
  }

  async function callChat(message) {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, responseLanguage: getResponseLanguage() })
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return await res.json(); // { reply, emotion }
  }

  async function sendMessage() {
    const message = consoleInput.value.trim();
    if (!message) return;
    const userRow = appendLog('user', message);
    consoleInput.value = '';
    consoleInput.disabled = true;
    consoleSend.disabled = true;
    consoleTag.textContent = 'THINKING...';
    try {
      const data = await callChat(message);
      attachEmotionBadge(userRow, data.emotion);
      appendLog('lumi', data.reply);
      speak(data.reply);
    } catch (err) {
      appendLog('lumi', '통신 중 문제가 발생했습니다. 서버 실행 상태를 확인해주세요.');
      console.error(err);
    } finally {
      consoleInput.disabled = false;
      consoleSend.disabled = false;
      consoleTag.textContent = 'STANDBY';
      consoleInput.focus();
      refreshSchedules();
      refreshUsage();
    }
  }

  consoleSend.addEventListener('click', sendMessage);
  consoleInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendMessage();
  });

  // 페이지 로드 시: 인사 + 일정 브리핑을 한 번 자동 요청 (페르소나 시스템 프롬프트 §4 규칙)
  (async function initGreeting() {
    consoleTag.textContent = 'CONNECTING...';
    try {
      const data = await callChat('(대화 시작)');
      appendLog('lumi', data.reply);
      // 페이지 로드와 동시에 자동으로 뜨는 첫 인사말이라 아직 사용자가 클릭/입력 등으로
      // 페이지와 상호작용한 적이 없다 — 이 상태에서 오디오를 재생하면 브라우저 자동재생
      // 정책에 막혀 NotAllowedError가 난다(Fish Audio <audio> 재생도, speechSynthesis도
      // 동일하게 막힐 수 있음). 그래서 첫 인사말은 텍스트만 보여주고 음성은 건너뛴다.
      // 이후 사용자가 메시지를 보내거나 마이크를 누르는 순간부터는(이미 클릭/입력이라는
      // 상호작용이 있었으므로) speak()가 정상적으로 재생된다.
    } catch (err) {
      appendLog('lumi', '서버에 연결하지 못했습니다. Spring Boot 애플리케이션이 실행 중인지 확인해주세요.');
      console.error(err);
    } finally {
      consoleTag.textContent = 'STANDBY';
      refreshSchedules();
      refreshUsage();
    }
  })();
