# 수집(Ingestion)과 검색(Retrieval) 운영

## 수집(Ingestion)

- 정책 조건에 맞는 응답을 candidate로 캡처
- 리뷰 상태를 저장
- 정책 허용 시 벡터 스토어에 실제 반영

## 검색(Retrieval)

- 메타데이터 필터를 포함한 시맨틱 검색 수행
- 문서 중복 제거 및 점수 기반 정렬
- `topK`/threshold를 응답 토큰 예산과 함께 조정

## 운영 체크

- 대상 프로필에서 ingestion 관련 스토어/테이블 활성화 확인
- 검색 지연 시간 및 토큰 사용량 모니터링
- 실패 경로(스토어 장애, 잘못된 필터) 검증

## 다음 문서

- [Memory/RAG 아키텍처](architecture.md)
- [딥다이브](deep-dive.md)
