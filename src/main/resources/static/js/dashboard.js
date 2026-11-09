// TODAY'S SCHEDULE 패널(일정 목록·겹침 감지)과 API 사용량 대시보드

  const schedList = document.getElementById('schedList');
  const schedCount = document.getElementById('schedCount');
  const alertCard = document.getElementById('alertCard');
  const alertBody = document.getElementById('alertBody');
  const usageReadout = document.getElementById('usageReadout');
  const usageBarChart = document.getElementById('usageBarChart');
  const usageBarLabels = document.getElementById('usageBarLabels');

  async function refreshSchedules() {
    try {
      const res = await fetch('/api/schedules');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      renderSchedules(data.schedules || []);
      upsertToast('schedule', 'success', 'SCHEDULE FETCH OK', '일정 조회 성공');
    } catch (err) {
      console.error('일정 조회 실패:', err);
      upsertToast('schedule', 'error', 'SCHEDULE FETCH FAILED', '일정 조회 실패: ' + err.message);
    }
  }

  async function refreshUsage() {
    try {
      const res = await fetch('/api/usage');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      renderUsageChart(data);
      upsertToast('usage', 'success', 'USAGE FETCH OK', 'API 사용량 조회 성공');
    } catch (err) {
      console.error('API 사용량 조회 실패:', err);
      upsertToast('usage', 'error', 'USAGE FETCH FAILED', 'API 사용량 조회 실패: ' + err.message);
    }
  }

  function renderUsageChart(data) {
    usageReadout.textContent = data.today + ' / ' + data.dailyLimit;
    const days = data.last7Days || [];
    usageBarChart.innerHTML = '';
    usageBarLabels.innerHTML = '';
    if (!days.length) return;

    const maxCount = Math.max(1, ...days.map(d => d.count));
    const todayStr = new Date().toISOString().slice(0, 10);

    days.forEach(d => {
      const pct = Math.max(4, Math.round((d.count / maxCount) * 100));
      const bar = document.createElement('div');
      bar.className = 'bar' + (d.date === todayStr ? ' today' : '');
      bar.style.height = pct + '%';
      usageBarChart.appendChild(bar);

      const label = document.createElement('span');
      label.textContent = d.label;
      usageBarLabels.appendChild(label);
    });
  }

  // 서버는 각 일정을 개별로만 알고 있으므로(hasConflict는 add 시점에만 계산),
  // 화면에 표시할 전체 목록 기준 겹침은 여기서 다시 한 번 계산한다.
  function renderSchedules(schedules) {
    schedCount.textContent = schedules.length + ' ITEMS';
    schedList.innerHTML = '';

    if (schedules.length === 0) {
      const row = document.createElement('div');
      row.className = 'sched-row';
      row.innerHTML = '<span class="sched-title" style="color:#4A6472;">등록된 일정이 없습니다</span>';
      schedList.appendChild(row);
      alertCard.style.display = 'none';
      return;
    }

    const sorted = [...schedules].sort((a, b) => new Date(a.startTime) - new Date(b.startTime));
    const now = new Date();
    const conflicts = [];
    for (let i = 0; i < sorted.length; i++) {
      const aStart = new Date(sorted[i].startTime);
      const aEnd = sorted[i].endTime ? new Date(sorted[i].endTime) : new Date(aStart.getTime() + 3600000);
      for (let j = i + 1; j < sorted.length; j++) {
        const bStart = new Date(sorted[j].startTime);
        const bEnd = sorted[j].endTime ? new Date(sorted[j].endTime) : new Date(bStart.getTime() + 3600000);
        if (aStart < bEnd && bStart < aEnd) conflicts.push([sorted[i], sorted[j]]);
      }
    }

    let nextAssigned = false;
    sorted.forEach(s => {
      const start = new Date(s.startTime);
      const isNext = !nextAssigned && start >= now;
      if (isNext) nextAssigned = true;
      const hasConflict = conflicts.some(([a, b]) => a === s || b === s);

      const row = document.createElement('div');
      row.className = 'sched-row' + (isNext ? ' next' : '');
      const time = document.createElement('span');
      time.className = 'sched-time';
      time.textContent = pad(start.getHours()) + ':' + pad(start.getMinutes());
      const box = document.createElement('div');
      const title = document.createElement('div');
      title.className = 'sched-title';
      title.textContent = s.title;
      box.appendChild(title);
      if (hasConflict) {
        const note = document.createElement('div');
        note.className = 'sched-note';
        note.textContent = 'CONFLICT';
        box.appendChild(note);
      }
      row.appendChild(time);
      row.appendChild(box);
      schedList.appendChild(row);
    });

    if (conflicts.length > 0) {
      const [a, b] = conflicts[0];
      alertBody.textContent = a.title + '과(와) ' + b.title + ' 일정이 겹칩니다. 조정이 필요할까요?';
      alertCard.style.display = '';
    } else {
      alertCard.style.display = 'none';
    }
  }
