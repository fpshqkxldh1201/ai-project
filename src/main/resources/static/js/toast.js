// 화면 우측 하단 토스트 알림 — 폴링 결과용 슬롯 유지형(upsertToast)과 1회성(showToast) 두 가지

  const toastContainer = document.getElementById('toastContainer');

  const toastRegistry = {};

  // key로 슬롯을 하나만 유지한다: 실패하면 그 슬롯을 계속 띄워두고(안 사라짐),
  // 이후 성공하면 같은 슬롯을 성공 메시지로 바꾼 뒤 잠시 후 스스로 사라진다.
  // 처음부터 성공(이전에 에러 표시가 없었던 경우)이면 굳이 토스트를 띄우지 않는다.
  function upsertToast(key, status, title, detail) {
    let toast = toastRegistry[key];
    if (status === 'success' && !toast) return;

    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'toast';
      const titleEl = document.createElement('span');
      titleEl.className = 'toast-title';
      const bodyEl = document.createElement('span');
      bodyEl.className = 'toast-body';
      toast.appendChild(titleEl);
      toast.appendChild(bodyEl);
      toastContainer.appendChild(toast);
      toastRegistry[key] = toast;
    }

    toast.classList.toggle('toast-success', status === 'success');
    toast.querySelector('.toast-title').textContent = title;
    toast.querySelector('.toast-body').textContent = detail;

    if (toast._dismissTimer) clearTimeout(toast._dismissTimer);

    if (status === 'success') {
      toast._dismissTimer = setTimeout(() => {
        toast.style.animation = 'toast-out 0.25s ease-in forwards';
        setTimeout(() => {
          toast.remove();
          delete toastRegistry[key];
        }, 250);
      }, 3000);
    }
    // 실패 상태는 타이머를 걸지 않는다 — 다음 재조회가 성공할 때까지 화면에 고정.
  }

  // upsertToast와 달리 슬롯을 유지하지 않는 1회성 알림 — STT처럼 폴링이 아니라
  // 사용자 행동으로 한 번 발생하는 이벤트에 사용한다. 일정 시간 후 항상 사라진다.
  function showToast(status, title, detail, durationMs) {
    const toast = document.createElement('div');
    toast.className = 'toast' + (status === 'success' ? ' toast-success' : '');
    const titleEl = document.createElement('span');
    titleEl.className = 'toast-title';
    titleEl.textContent = title;
    const bodyEl = document.createElement('span');
    bodyEl.className = 'toast-body';
    bodyEl.textContent = detail;
    toast.appendChild(titleEl);
    toast.appendChild(bodyEl);
    toastContainer.appendChild(toast);
    setTimeout(() => {
      toast.style.animation = 'toast-out 0.25s ease-in forwards';
      setTimeout(() => toast.remove(), 250);
    }, durationMs || 4000);
  }
