# MCP 트랜스포트와 보안

## 지원 트랜스포트

- `STDIO`: 로컬 프로세스 기반
- `SSE`: 원격 HTTP SSE 기반
- `HTTP (streamable)`: 현재 SDK 경로에서 미지원, SSE 사용 권장

## 런타임 검증과 빠른 실패

Arc Reactor는 가능한 경우 초기화 전에 설정 오류를 빠르게 거절합니다.

- SSE URL 누락/형식 오류 조기 거절
- STDIO 절대 경로 커맨드 미존재 조기 거절
- request/initialization timeout을 connection timeout과 정렬

이 설정으로 잘못된 MCP 정의 때문에 테스트/기동이 오래 멈추는 문제를 줄일 수 있습니다.

## 보안 권장 사항

- 인증은 런타임 필수이므로 admin API는 역할 기반 검사로 보호
- 운영 환경에서 MCP 서버 allowlist 사용
- MCP 도구 출력은 신뢰하지 말고 정책/가드로 방어

## 다음 문서

- [런타임 등록과 Admin API](runtime-management.md)
- [트러블슈팅](troubleshooting.md)
