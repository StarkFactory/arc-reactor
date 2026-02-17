# MCP 통합 가이드 (개요)

이 문서는 Arc Reactor MCP 문서의 진입점입니다.

## 최근 반영 사항

- 서버 update 경로에서 store와 runtime manager 상태를 동기화
- 잘못된 엔드포인트/설정에서 더 빠르게 실패하도록 연결 경로 개선
- 재연결 동작은 기존 정책(백오프/제한)을 유지
- 현재 사용 중인 MCP SDK 경로에서는 streamable HTTP 미지원

## 주제별 문서

- [트랜스포트와 보안](mcp/transports-and-security.md)
- [런타임 등록과 Admin API](mcp/runtime-management.md)
- [MCP 트러블슈팅](mcp/troubleshooting.md)
- [딥다이브(기존 전체 문서)](mcp/deep-dive.md)

## 관련 문서

- [도구 레퍼런스](../reference/tools.md)
- [ReAct 루프](react-loop.md)
