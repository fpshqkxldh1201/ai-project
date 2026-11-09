# 검증셋(head, seed 0): 오분류 13개 / 116개

| 문장 | 정답 | 예측 | hard |
|---|---|---|---|
| 캘린더에 뭐 들어 있는지 말해줘 | schedule_get | reminder_get | 0 |
| What's my day looking like? | schedule_get | chat_emotion | 1 |
| 목요일에 치과 몇 시로 해놨더라 | schedule_get | schedule_add | 0 |
| Block off Thursday 2 to 4 pm for lab work | schedule_add | reminder_add | 0 |
| 모레 저녁에 약속 겹치는 거 없는지 체크해줘 | schedule_get | schedule_add | 0 |
| 수요일 저녁에 동생 생일 축하 연락하라고 알림 만들어줘 | reminder_add | schedule_add | 0 |
| 주말에 해야 할 일 뭐 걸려 있나 확인 | reminder_get | schedule_get | 0 |
| 스프링 부트 4 마이그레이션 가이드 찾아봐 | search_web | schedule_add | 0 |
| 남은 리마인더들 모두 말해 줘 | schedule_get | reminder_get | 0 |
| 오전 열한 시 알람은 왜 설정된 거야 | reminder_get | reminder_add | 0 |
| k. b. s. 뉴스 가져와 | search_web | search_video | 0 |
| 맑은 날씨네 | search_web | chat_emotion | 0 |
| 굿모닝 | chat_emotion | search_video | 0 |