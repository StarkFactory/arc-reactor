# MCP 런타임 등록과 Admin API

## 라이프사이클

1. 서버 정의를 store에 등록/수정
2. runtime manager 상태 동기화
3. 정책에 따라 connect/disconnect 수행
4. 연결된 도구를 요청 시점 도구 선택에 반영

## 핵심 동작

- update 시 store만 바꾸면 안 되고 runtime manager 상태도 동기화해야 함
- 재연결은 최신 서버 정의를 기준으로 동작해야 함
- admin write API는 auth 모드에 맞는 권한 검증 필요

## 운영 체크리스트

- create/update 후 상태 확인
- 기대한 tool callback이 노출되는지 확인
- 실패 재시도 시 로그/부하가 과도하지 않은지 확인

## 관련 문서

- [MCP 개요](../mcp.md)
- [MCP 트러블슈팅](troubleshooting.md)
