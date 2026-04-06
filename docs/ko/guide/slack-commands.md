# Reactor Slack 명령어 가이드

Slack에서 `/reactor` 명령어로 Reactor AI 어시스턴트를 사용할 수 있습니다.

---

## 검색 & 질문

### `/reactor search <검색어>`
사내 문서(Confluence), 이슈(Jira), 코드(Bitbucket)를 통합 검색합니다.

```
/reactor search 연차 정책
/reactor search 배포 프로세스
/reactor search 신입사원 온보딩
```

### `/reactor ask <질문>`
사내 정책이나 규정에 대해 질문합니다. Confluence 문서를 우선 검색하여 근거 기반으로 답변합니다.

```
/reactor ask 재택근무 가능한가요?
/reactor ask 경조사 지원금 얼마야?
/reactor ask 연차 신청 절차가 어떻게 돼?
```

### `/reactor who <이름/역할>`
사람, 조직, 담당자를 찾습니다. Slack 프로필과 사내 도구를 검색합니다.

```
/reactor who 최진안
/reactor who 프론트엔드 담당
/reactor who 인사팀장
```

---

## 업무 브리핑

### `/reactor brief [주제]`
오늘의 우선순위, 리스크, 다음 행동을 정리한 일일 브리핑을 생성합니다.

```
/reactor brief
/reactor brief 이번주 배포 준비
```

### `/reactor my-work [범위]`
내 업무 현황을 진행 중 / 대기 / 다음으로 분류하여 요약합니다.

```
/reactor my-work
/reactor my-work 이번주
```

---

## 유틸리티

### `/reactor summarize [텍스트]`
텍스트나 현재 대화를 한국어로 간결하게 요약합니다. 핵심 포인트, 결정사항, 후속 조치를 정리합니다.

```
/reactor summarize
/reactor summarize 긴 이메일 내용을 여기에 붙여넣기...
```

### `/reactor translate <텍스트>`
한국어 ↔ 영어 자동 감지 번역. 비즈니스 톤을 유지하고 전문 용어는 원어를 괄호에 병기합니다.

```
/reactor translate Please review the attached proposal and provide feedback by EOD.
/reactor translate 다음 주 목요일까지 설계 리뷰 부탁드립니다.
```

---

## 주기적 스케줄 (Loop)

### `/reactor loop <간격> <내용>`
주기적으로 실행되는 업무 브리핑을 등록합니다 (유저당 최대 5개).

```
/reactor loop 9am 내 Jira 이슈 요약해줘
/reactor loop daily 마감 임박 이슈 알려줘
/reactor loop weekly 주간 업무 정리해줘
/reactor loop 30m 빌드 상태 확인해줘
```

**지원 간격:**
| 형식 | 예시 | 설명 |
|------|------|------|
| 분 | `30m`, `45m` | N분마다 (최소 30분) |
| 시간 | `1h`, `2h` | N시간마다 |
| 시각 | `9am`, `2pm`, `14:30` | 매일 해당 시각 |
| 한국어 | `9시`, `14시30분` | 매일 해당 시각 |
| 키워드 | `daily`, `weekly`, `weekday` | 매일/매주/평일 오전 9시 |
| 한국어 | `매일`, `매주`, `평일` | 위와 동일 |

### `/reactor loop list`
내 스케줄 목록을 확인합니다.

### `/reactor loop stop <번호>`
특정 스케줄을 삭제합니다.

### `/reactor loop clear`
모든 스케줄을 삭제합니다.

---

## 리마인더

### `/reactor remind <내용>`
리마인더를 저장합니다. `at HH:mm`을 추가하면 해당 시각에 DM으로 알림합니다.

```
/reactor remind 디자인 리뷰 피드백 보내기
/reactor remind at 15:00 팀 미팅 참석
/reactor remind 3시에 배포 확인
```

### `/reactor remind list`
저장된 리마인더 목록을 확인합니다.

### `/reactor remind done <번호>`
리마인더를 완료 처리합니다.

### `/reactor remind clear`
모든 리마인더를 삭제합니다.

---

## 일반 질문

### `/reactor <아무 질문>`
위 명령어에 해당하지 않는 질문은 AI 에이전트가 직접 답변합니다.

```
/reactor Python에서 리스트 정렬하는 방법
/reactor Docker 컨테이너 재시작하는 명령어
/reactor 이번 분기 매출 목표가 뭐야?
```

---

## 팁

- 채널에서 **@Reactor** 멘션으로도 대화할 수 있습니다 (스레드 자동 생성)
- 봇 응답에 👍 👎 반응으로 피드백을 줄 수 있습니다
- 도움말: `/reactor help`
