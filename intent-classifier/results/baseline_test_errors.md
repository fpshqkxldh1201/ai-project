# 테스트셋: 오분류 11개 / 49개

| 문장 | 정답 | 예측 | hard |
|---|---|---|---|
| 금요일 저녁에 다른 일정 겹치는 거 없나? | schedule_get | schedule_add | 0 |
| 에펠탑 높이가 몇 미터야 | search_web | schedule_get | 1 |
| 주말에 쓰레기 버리는 거 까먹지 않게 해줘 | reminder_add | note_save | 0 |
| 요즘 잠을 잘 못 자서 피곤해 | chat_emotion | note_save | 0 |
| Do I have any meetings tomorrow? | schedule_get | reminder_get | 0 |
| Create an event for the study group on Thursday from 4 to 6 pm | schedule_add | reminder_add | 0 |
| Never mind, don't keep that in your memory | note_delete | note_save | 1 |
| Who won the game last night? | search_web | search_video | 0 |
| My calendar is so packed I could cry | chat_emotion | schedule_get | 1 |
| 다음 주 월요일에 뭐 잡혀 있지? | schedule_get | reminder_get | 0 |
| Don't forget that I'm vegetarian | note_save | note_delete | 0 |