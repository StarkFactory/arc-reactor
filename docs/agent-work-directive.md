# Arc Reactor Agent Work Directive

- 목적: Arc Reactor에서 AI agent 관련 기능을 설계/구현할 때, 이 문서 하나만 보고 작업 지시할 수 있도록 만든 실행 기준
- 범위: 조사 요약이 아니라 `무엇을 참고하고`, `무엇을 만들고`, `무엇을 금지하는지`를 정리한 작업 지침

## 1) 작업 원칙

1. `코드 재사용 금지`
   - 외부 OSS의 코드, 시스템 프롬프트, 파일 구조를 그대로 복사하지 않는다
2. `아이디어 참조만 허용`
   - 공개 OSS와 논문에서 아키텍처, 운영 패턴, 제어 구조만 참고한다
3. `코드 강제가 프롬프트보다 우선`
   - 정책, 권한, 종료 조건, 승인 흐름은 가능하면 코드로 강제한다
4. `기본값은 보수적`
   - 새로운 agent 기능은 모두 `opt-in`으로 시작한다
5. `측정 없는 개선 금지`
   - 체감 개선이 아니라 평가셋/메트릭 기준으로 개선을 판단한다

## 2) 외부 참고 기준

### 2.1) 아이디어 참조 가능한 프로젝트

아래 프로젝트들은 구조와 아키텍처 아이디어를 참고할 수 있다.

1. `openclaw`
2. `claw-code`
3. `cline`
4. `aider`
5. `goose`
6. `Roo-Code`
7. `qwen-code`
8. `LangGraph`
9. `CrewAI`
10. `OpenHands`
11. `browser-use`
12. `OpenAI Agents SDK`
13. `Google ADK`
14. `PydanticAI`
15. `Haystack`

### 2.2) 특히 주의할 프로젝트

1. `claw-code`
   - 라이선스가 명확하지 않으므로 `코드/프롬프트/문장/파일 구조` 복제 금지
   - 아키텍처 아이디어만 참고
2. `openclaw`
   - MIT 라이선스이지만, 이 프로젝트에서는 여전히 `아이디어 참조`만 한다
   - 직접 포팅보다 Arc Reactor 용어와 구조로 재설계한다

### 2.3) 시스템 프롬프트 차용 규칙

1. 차용 가능한 것
   - 블록 구조
   - 정책 계층 분리 방식
   - tool policy / workspace rule / memory hint 같은 구분
2. 차용하면 안 되는 것
   - 문장 원문
   - 긴 규칙 블록
   - 고유한 phrasing
3. 항상 이렇게 한다
   - `구조만 추출 -> Arc Reactor 정책으로 재작성 -> 코드 강제 가능한 것은 프롬프트에서 제거`

## 3) Arc Reactor에 우선 도입할 패턴

아래 5개가 현재 최우선이다.

### 3.1) Tool Approval UX 강화

- 목표:
  사용자 승인 전에 `왜`, `무엇을`, `영향 범위`, `되돌릴 수 있는지`를 구조적으로 보여준다
- 적용 위치:
  `ToolApprovalPolicy`, REST/SSE 응답, agent execution UI 계층

### 3.2) Agent-Computer Interface 정비

- 목표:
  도구 출력과 repo 탐색 인터페이스를 `agent 친화적`으로 바꾼다
- 적용 위치:
  tool output sanitizer, 파일 검색/읽기/실행 결과 요약 계층

### 3.3) Patch-First Editing Loop

- 목표:
  전체 재작성보다 `locate -> patch -> explain`을 기본 경로로 둔다
- 적용 위치:
  코드 수정 에이전트 경로, tool selection 우선순위

### 3.4) Prompt Layer 분리

- 목표:
  시스템 프롬프트를 하나의 문자열이 아니라 계층으로 관리한다
- 기본 계층:
  - `identity`
  - `safety`
  - `tool_policy`
  - `workspace_policy`
  - `response_style`
  - `memory_hint`
- 적용 위치:
  prompt builder, configuration, workspace override 계층

### 3.5) Benchmark-Aware Evaluation Loop

- 목표:
  기능 추가 전후를 평가셋으로 비교한다
- 기본 지표:
  - task success rate
  - average tool calls
  - latency
  - token cost
  - human override rate
  - safety rejection accuracy
- 적용 위치:
  hardening tests 외 별도 evaluation pipeline

## 4) 두 번째 단계 패턴

아래는 1차 도입 후 고려한다.

1. `Graph Mode`
   - `plan -> retrieve -> tool -> review -> finalize`
2. `Agentless Decomposition`
   - `localization -> repair -> rerank`
3. `Session Class / Sandbox Tier`
   - `read_only`, `workspace_write`, `sandbox_exec`, `privileged`
4. `Bounded Reflection Memory`
   - 짧고 만료 가능한 반성 메모리
5. `Doctor Command`
   - 설정/권한/도구/MCP 상태 진단

## 5) 외부 프로젝트에서 실제로 배울 점

### 5.1) `claw-code`에서 배울 점

1. `doctor-first UX`
   - 작업 전에 환경을 점검하게 하는 흐름
2. `운영 문서 분리`
   - usage, philosophy, roadmap, parity를 코드와 분리
3. `canonical runtime 명시`
   - 정식 경로와 실험 경로를 분리해 사용자 혼란을 줄임
4. `container-first 운영`
   - 실행 재현성과 격리를 운영 설계에 포함

### 5.2) `openclaw`에서 배울 점

1. `control plane` 관점
   - agent를 단일 프롬프트가 아니라 세션/도구/이벤트/권한의 조합으로 본다
2. `prompt layering`
   - `AGENTS`, `SOUL`, `TOOLS`처럼 역할별로 프롬프트를 분리
3. `session-aware permissions`
   - 메인 세션과 비메인 세션을 다르게 다룸
4. `skill lifecycle`
   - skills를 동적 확장 단위로 취급
5. `capability discovery`
   - 어떤 환경이 어떤 도구와 권한을 갖는지 구조적으로 알림

## 6) 논문 기반으로 우선 신뢰할 패턴

작업 시 아래 순서로 우선 참고한다.

1. `ReAct`
   - 기본 reasoning + acting 루프
2. `SWE-agent`
   - Agent-Computer Interface 설계
3. `Agentless`
   - 자율 루프 대신 단계 분해
4. `Reflexion`
   - 단, bounded memory로 제한해서 사용
5. `LATS`
   - 기본값이 아니라 hard mode 전용

## 7) 작업 지시 템플릿

다른 문서 보지 말고, 앞으로 agent 작업은 아래 형식으로 지시하면 된다.

```text
다음 작업은 docs/agent-work-directive.md 기준으로 진행.

목표:
- [구현 목표]

이번 작업에서 적용할 패턴:
- [예: Tool Approval UX 강화]
- [예: Prompt Layer 분리]

제약:
- 외부 OSS 코드/프롬프트 복사 금지
- Arc Reactor 기존 원칙(Guard fail-close, Hook fail-open, maxToolCalls, message pair integrity) 유지
- opt-in으로만 도입

산출물:
- [예: 설계 문서]
- [예: 코드 변경]
- [예: 테스트]
```

## 8) 작업자가 절대 하면 안 되는 것

1. `claw-code`나 다른 OSS의 코드/프롬프트를 그대로 가져오기
2. 기본값으로 멀티 에이전트/자유 루프를 켜기
3. 승인 없는 위험 도구 실행 경로 만들기
4. 평가셋 없이 “좋아졌다”고 판단하기
5. 메모리를 무제한 누적하기

## 9) 이 문서만 보면 되는 이유

1. 조사 결과를 이미 실행 원칙으로 압축했다
2. `무엇을 참고할지`, `무엇을 만들지`, `무엇을 금지할지`가 다 들어 있다
3. 작업 지시 템플릿까지 포함되어 있어 바로 실행 가능하다
