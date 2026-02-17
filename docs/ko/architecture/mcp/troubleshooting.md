# MCP 트러블슈팅 (현재 기준)

## HTTP 트랜스포트가 연결되지 않음

streamable HTTP 설정 시 연결되지 않는다면:

- `SSE` 트랜스포트로 전환
- 현재 SDK 경로에서 streamable HTTP는 지원되지 않음

## SSE URL 오류

점검 항목:

- 절대 `http://` 또는 `https://` URL
- 실제 도달 가능한 엔드포인트
- 합리적인 timeout 설정

## STDIO command not found

점검 항목:

- 절대 경로 커맨드 파일 존재 여부
- 런타임 PATH에 커맨드가 있는지
- 시작 인자(args) 오류 여부

## MCP 관련 테스트가 느릴 때

원인:

- 테스트에서 connect/initialize timeout이 길게 설정됨
- 실패 케이스에서도 reconnect 루프가 활성화됨

권장:

- 테스트용 connection timeout 단축
- reconnect 동작 검증 테스트가 아니면 비활성화
- 빠르게 실패하는 고정 invalid endpoint 사용

## 다음 문서

- [트랜스포트와 보안](transports-and-security.md)
- [런타임 등록과 Admin API](runtime-management.md)
