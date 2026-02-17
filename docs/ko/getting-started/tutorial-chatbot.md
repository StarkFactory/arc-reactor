# 30분 챗봇 튜토리얼 (요약)

이 문서는 빠른 시작용 요약 튜토리얼입니다.

## 1) 환경 준비

```bash
export GEMINI_API_KEY=your-api-key
./gradlew :arc-app:bootRun
```

## 2) 기본 채팅 요청

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "userPrompt": "간단한 인사 메시지를 작성해줘",
    "userId": "demo-user"
  }'
```

## 3) 도구/정책 기능 확장

권장 확인 순서:

1. 기본 응답이 정상인지 확인
2. 도구 활성화 및 호출 흐름 확인
3. Guard/Hook 정책을 점진적으로 추가
4. 필요 시 MCP 서버 연결

## 4) 운영 전 체크

- 실행 명령이 `:arc-app:bootRun`인지 확인
- 통합 테스트는 필요 시 `-PincludeIntegration`로만 실행
- 운영 환경에서는 관리자 API와 인증 정책을 반드시 검토

## 자세한 튜토리얼

- 영문 상세 튜토리얼: [EN Tutorial](../../en/getting-started/tutorial-chatbot.md)
