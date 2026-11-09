// 우측 상단 디지털 시계

  // 시계
  const clock = document.getElementById('clock');
  function pad(n) { return n.toString().padStart(2, '0'); }
  function tick() {
    const now = new Date();
    clock.textContent = pad(now.getHours()) + ':' + pad(now.getMinutes()) + ':' + pad(now.getSeconds());
  }
  tick(); setInterval(tick, 1000);
