# /loop Slash Command 설계

## 개요

사용자가 Slack에서 `/loop` 명령어로 주기적 업무 브리핑/알림을 자연어로 설정할 수 있는 기능.

## 사용법

```
/loop 30m 내 Jira 이슈 확인해줘           → 30분마다 실행
/loop 9am 오늘 일정 브리핑해줘            → 매일 오전 9시
/loop daily 마감 임박 이슈 알려줘          → 매일 오전 9시 (기본)
/loop weekly 주간 업무 정리해줘            → 매주 월요일 오전 9시
/loop list                               → 내 스케줄 목록
/loop stop 2                             → 2번 스케줄 중지
/loop clear                              → 전체 스케줄 삭제
```

## 제한

| 항목 | 값 | 사유 |
|------|-----|------|
| 유저당 최대 스케줄 | 5개 | 시스템 과부하 방지 |
| 최소 간격 | 30분 | LLM 비용 + 채널 스팸 방지 |
| 전체 시스템 최대 | 1,500개 | 300명 × 5 기준 |
| 결과 전달 | DM (기본) 또는 현재 채널 | 채널 스팸 방지 |

## 인터벌 파싱 규칙

| 입력 | cron 표현식 | 설명 |
|------|------------|------|
| `30m` | `0 */30 * * * *` | 30분마다 |
| `1h` | `0 0 * * * *` | 1시간마다 |
| `9am` | `0 0 9 * * *` | 매일 오전 9시 |
| `14:30` | `0 30 14 * * *` | 매일 오후 2:30 |
| `daily` | `0 0 9 * * *` | 매일 오전 9시 |
| `weekly` | `0 0 9 * * MON` | 매주 월요일 오전 9시 |
| `weekday` | `0 0 9 * * MON-FRI` | 평일 오전 9시 |

## 아키텍처

```
유저: /loop 9am 내 이슈 요약해줘
  ↓
SlackCommandProcessor
  ↓
SlackSlashIntentParser → SlackSlashIntent.Loop
  ↓
DefaultSlackCommandHandler.handleLoop()
  ↓ 유저당 5개 제한 체크
  ↓ 인터벌 → cron 변환
  ↓
DynamicSchedulerService.registerJob()
  ↓ ScheduledJob (jobType=AGENT, agentPrompt="내 이슈 요약해줘")
  ↓ slackChannelId = userId (DM)
  ↓
DB 저장 (scheduled_jobs 테이블)
  ↓
cron 트리거 등록
  ↓
(매일 9시)
DynamicSchedulerService.runScheduledJob()
  ↓ AgentExecutor.execute(prompt="내 이슈 요약해줘")
  ↓ Jira/Confluence 도구 호출
  ↓
SlackMessagingService.sendMessage(channelId=userId, text=결과)
  ↓
유저 DM으로 전달
```

## 구현 대상 파일

### 1. SlackSlashIntent.kt — Loop 인텐트 추가

```kotlin
sealed interface SlackSlashIntent {
    // ... 기존 ...

    data class LoopCreate(val interval: String, val prompt: String) : SlackSlashIntent
    object LoopList : SlackSlashIntent
    data class LoopStop(val id: Int) : SlackSlashIntent
    object LoopClear : SlackSlashIntent
}
```

### 2. SlackSlashIntentParser — Loop 파싱 추가

```kotlin
// 파싱 규칙
"loop list" | "loop 목록" → LoopList
"loop stop N" | "loop 중지 N" → LoopStop(N)
"loop clear" | "loop 전체삭제" → LoopClear
"loop INTERVAL PROMPT" → LoopCreate(interval, prompt)
```

### 3. DefaultSlackCommandHandler — Loop 핸들러

```kotlin
fun handleLoopCreate(command, intent) {
    // 1. 유저당 스케줄 수 체크 (5개 제한)
    // 2. 인터벌 → cron 변환
    // 3. ScheduledJob 생성
    //    - jobType = AGENT
    //    - agentPrompt = intent.prompt
    //    - slackChannelId = command.userId (DM)
    //    - tags = setOf("user-loop", command.userId)
    // 4. DynamicSchedulerService.registerJob()
    // 5. 응답: "✅ 스케줄 등록 (N/5)"
}

fun handleLoopList(command) {
    // tags에 userId로 필터 → 목록 반환
}

fun handleLoopStop(command, intent) {
    // 소유권 확인 → job.enabled = false
}
```

### 4. LoopIntervalParser — 인터벌 → cron 변환

```kotlin
object LoopIntervalParser {
    fun parse(interval: String): String? {
        return when {
            interval.matches("\\d+m") → minutesCron(interval)
            interval.matches("\\d+h") → hoursCron(interval)
            interval.matches("\\d{1,2}(am|pm)") → dailyTimeCron(interval)
            interval.matches("\\d{1,2}:\\d{2}") → dailyTimeCron24(interval)
            interval == "daily" → "0 0 9 * * *"
            interval == "weekly" → "0 0 9 * * MON"
            interval == "weekday" → "0 0 9 * * MON-FRI"
            else → null
        }
    }
}
```

## DB 스키마

기존 `scheduled_jobs` 테이블을 그대로 사용. 유저 Loop은 `tags` 컬럼으로 구분:
- `tags = "user-loop,U088WLGNT41"` (user-loop 태그 + userId)

유저당 스케줄 조회: `SELECT * FROM scheduled_jobs WHERE tags LIKE '%U088WLGNT41%' AND tags LIKE '%user-loop%'`

## Slack 앱 설정 가이드

### 필수: Slash Command 등록

1. https://api.slack.com/apps 에서 앱 선택
2. 왼쪽 메뉴 → **Slash Commands**
3. **Create New Command** 클릭
4. 설정:
   - Command: `/loop`
   - Request URL: Socket Mode 사용 시 불필요 (자동 라우팅)
   - Short Description: `주기적 업무 브리핑 설정`
   - Usage Hint: `[interval] [prompt] 또는 list/stop/clear`
5. **Save** → **Reinstall to Workspace**

### 필수: Bot Token Scope

`commands` scope가 필요 — 이미 권장 scope 목록에 포함되어 있음.

### Socket Mode 사용 시

Socket Mode가 켜져 있으면 Request URL 없이 WebSocket으로 자동 수신.
`SlackCommandProcessor`가 자동으로 `/loop` 명령을 처리.

## 응답 예시

### 생성
```
✅ 스케줄 등록 완료 (2/5)
📋 매일 09:00 KST
📝 내 Jira 이슈 요약
📮 전달: DM
```

### 목록
```
📋 내 스케줄 (2/5)
1. ⏰ 매일 09:00 — 내 Jira 이슈 요약
2. ⏰ 매주 월 09:00 — 주간 업무 정리
```

### 실행 결과 (DM)
```
🔔 스케줄 실행 결과 (매일 09:00 — 내 Jira 이슈 요약)

• 마감 임박: PROJ-123 "배포 준비" (내일까지)
• 신규 배정: PROJ-456 "버그 수정" (이번주)
• 리뷰 대기: PR #42 (김경훈님 요청)

출처
- https://ihunet.atlassian.net/browse/PROJ-123
```

### 에러
```
❌ 스케줄 등록 실패
최대 5개까지 등록할 수 있습니다. 기존 스케줄을 삭제하려면:
/loop list → /loop stop [번호]
```

## 자연어 지원 (추후)

`/loop` 외에 `@Reactor 매일 오전 9시에 브리핑해줘` 같은 자연어도 지원.
이 경우 LLM이 자연어를 파싱하여 `schedule_create` 도구를 호출.

## 의존성

- `DynamicSchedulerService` (arc-core) — 이미 존재
- `JdbcScheduledJobStore` (arc-core) — 이미 존재
- `SlackMessagingService` (arc-slack) — 이미 존재
- `DefaultSlackCommandHandler` (arc-slack) — 확장 필요
- `SlackSlashIntentParser` (arc-slack) — 확장 필요

## 우선순위

1. ✅ Slack 앱에 `/loop` 명령어 등록 (Admin 작업)
2. ✅ `commands` scope 확인
3. `SlackSlashIntent.Loop*` 인텐트 추가
4. `LoopIntervalParser` 구현
5. `DefaultSlackCommandHandler` Loop 핸들러 구현
6. 유저당 5개 제한 로직
7. 테스트
