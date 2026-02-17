# Memory/RAG 아키텍처

## 핵심 구성요소

- memory store, conversation manager
- query transformer, retriever
- reranker, 응답 조립 계층
- ingestion candidate/policy store

## 요청 경로 (요약)

1. 세션 기반 대화 컨텍스트 로드
2. 필요 시 질의 변환
3. 문서 검색/랭킹
4. 프롬프트에 RAG 컨텍스트 주입
5. 출력/응답 정책 적용

## 설계 노트

- Retrieval은 기능 플래그에 따라 선택적으로 동작
- 단계별 fail-open/fail-close 정책을 명시적으로 유지
- Guard/Hook의 동작 일관성 유지

## 다음 문서

- [수집과 검색 운영](ingestion-and-retrieval.md)
- [딥다이브](deep-dive.md)
