# Arc Reactor 모듈 문서

Arc Reactor 프레임워크의 모듈별 레퍼런스 문서입니다. 각 문서는 핵심 컴포넌트, 모든 설정 프로퍼티(실제 코드에서 직접 가져옴), 확장 지점, API 레퍼런스(해당하는 경우), 실용적인 코드 예시, 그리고 자주 발생하는 실수를 다룹니다.

## 모듈

| 모듈 | 설명 |
|---|---|
| [arc-core](./arc-core.md) | 에이전트 실행기, Guard 파이프라인, Hook 시스템, Tool 추상화, 자동 구성, RAG, 메모리, Prompt Lab, 복원력 |
| [arc-web](./arc-web.md) | REST 컨트롤러, SSE 스트리밍, 보안 필터, 인증, 테넌트 해석, 전역 예외 처리기 |
| [arc-admin](./arc-admin.md) | 운영 제어 플레인: 메트릭, 추적, 테넌트 관리, 비용 추적, 알림, 할당량 강제 |
| [arc-slack](./arc-slack.md) | Socket Mode 또는 Events API를 통한 Slack 연동, 서명 검증 및 배압 제한 포함 |
| [arc-discord](./arc-discord.md) | Discord4J 게이트웨이를 통한 Discord 연동, 멘션 전용 모드 및 2000자 분할 포함 |
| [arc-line](./arc-line.md) | LINE Messaging API 연동, webhook 서명 검증 및 reply/push 폴백 포함 |
| [arc-error-report](./arc-error-report.md) | AI 기반 프로덕션 에러 분석: 스택 트레이스 수신, MCP 도구로 조사, Slack 보고 |

## 빠른 탐색

**이 작업을 하고 싶다면...**

- 커스텀 Tool 만들기 → [arc-core: ToolCallback](./arc-core.md#toolcallback----커스텀-tool)
- Guard 단계 추가 (입력 검증, 속도 제한) → [arc-core: GuardStage](./arc-core.md#guardstage----커스텀-guard-단계)
- 에이전트 라이프사이클에 Hook 연결 (감사, 과금) → [arc-core: Hook](./arc-core.md#hook----라이프사이클-확장)
- 메모리 및 대화 히스토리 설정 → [arc-core: 설정](./arc-core.md#메모리-요약-arcreactormemorysummary)
- RAG 활성화 → [arc-core: RAG 설정](./arc-core.md#rag-arcreactorrag)
- 모든 REST 엔드포인트 이해 → [arc-web: API 레퍼런스](./arc-web.md#api-레퍼런스)
- 보안 헤더 / CORS 추가 → [arc-web: 설정](./arc-web.md#설정)
- MCP 서버 등록 → [arc-web: MCP 서버](./arc-web.md#mcp-서버)
- JWT 인증 활성화 → [arc-web: 인증](./arc-web.md#인증-arcreactorauthenabled-true-필요)
- 오류 메시지 재정의 (다국어 지원) → [arc-core: ErrorMessageResolver](./arc-core.md#errormessageresolver----커스텀-오류-메시지)
