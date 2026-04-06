# 피드백 대시보드 설계

## 현재 상태

### 이미 있는 것
- `Feedback` 모델: query, response, rating(👍👎), userId, sessionId, toolsUsed, domain, model, durationMs
- `FeedbackStore`: JDBC 구현 + 필터 조회 (rating, from, to, intent, sessionId)
- `FeedbackController`: CRUD API + export
- Slack 리액션(👍👎) → 자동 피드백 수집 (`DefaultSlackEventHandler.handleReaction`)

### 없는 것
- 통계/분석 API (긍정/부정 비율, 트렌드, 자주 실패하는 주제)
- Admin 대시보드 뷰 (arc-reactor-admin에서 시각화)
- 피드백 → 프롬프트 개선 워크플로

## 피드백 루프 설계 (수동 반영)

```
사용자 👎 반응
  ↓
FeedbackStore에 자동 저장
  ↓
Admin 대시보드에서 조회
  ↓
Admin이 패턴 분석:
  - "Confluence 검색 질문에 부정 피드백이 많다"
  - "에스컬레이션 안내가 부족하다"
  ↓
Admin이 페르소나/프롬프트 수동 수정
  ↓
변경 이력 감사 로그 기록
```

**자동 반영은 하지 않음** — 300명 환경에서 자동 프롬프트 조정은 위험.

## 추가 필요한 API

### 1. 피드백 통계 API

```
GET /api/admin/feedback/stats?from=2026-04-01&to=2026-04-06

{
  "total": 150,
  "positive": 120,
  "negative": 30,
  "positiveRate": 0.80,
  "byDay": [
    {"date": "2026-04-05", "positive": 25, "negative": 5},
    {"date": "2026-04-06", "positive": 15, "negative": 3}
  ],
  "topNegativeTopics": [
    {"topic": "confluence_search", "count": 8},
    {"topic": "jira_query", "count": 5}
  ]
}
```

### 2. 부정 피드백 상세 목록 API

```
GET /api/admin/feedback/negative?limit=20

[
  {
    "feedbackId": "...",
    "query": "교육운영1팀 주간업무 알려줘",
    "response": "관련 정보를 찾지 못했습니다...",
    "rating": "NEGATIVE",
    "userId": "U088WLGNT41",
    "toolsUsed": ["confluence_search"],
    "timestamp": "2026-04-06T11:00:00Z"
  }
]
```

## 구현 순서

1. `FeedbackController`에 `/api/admin/feedback/stats` 엔드포인트 추가
2. `FeedbackStore`에 `countByRating(from, to)` 메서드 추가
3. Admin UI (arc-reactor-admin)에 피드백 대시보드 페이지
4. 부정 피드백 알림 (선택 — 일정 비율 초과 시 Slack 알림)

## 보안

- 피드백 목록에는 사용자 query/response가 포함되므로 **ADMIN 권한 필수**
- 피드백 데이터는 프롬프트 개선 목적으로만 사용
- 개인 식별 가능 정보는 마스킹 처리 권장
