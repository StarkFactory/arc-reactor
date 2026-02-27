# 아키텍처 패키지

Arc Reactor 내부 동작을 설명하는 아키텍처 문서 모음입니다.

## 핵심 흐름

- [아키텍처 개요](architecture.md)
- [모듈 레이아웃](module-layout.md)
- [프로젝트 백과사전(레이어/요청흐름)](layered-architecture.md)
- [ReAct 루프](react-loop.md)
- [Streaming ReAct](streaming-react.md)
- [Guard와 Hook](guard-hook.md)
- [응답 처리](response-processing.md)
- [복원력](resilience.md)
- [세션 관리](session-management.md)

## 고급 주제

- [멀티에이전트](multi-agent.md)
- [Supervisor 패턴](supervisor-pattern.md)
- [의도 분류](intent-classification.md)
- [Prompt Lab](prompt-lab.md)
- [Implementation Guide (EN)](../../en/architecture/implementation-guide.md)

## 하위 패키지

- MCP:
  - [개요](mcp.md)
  - [딥다이브](mcp/deep-dive.md)
  - [런타임 관리](mcp/runtime-management.md)
  - [전송/보안](mcp/transports-and-security.md)
  - [트러블슈팅](mcp/troubleshooting.md)
- Memory/RAG:
  - [개요](memory-rag.md)
  - [아키텍처](memory-rag/architecture.md)
  - [수집/검색](memory-rag/ingestion-and-retrieval.md)
  - [딥다이브](memory-rag/deep-dive.md)
