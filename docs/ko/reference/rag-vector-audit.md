# RAG / Vector 사용처 감사 문서

> 마지막 업데이트: 2026-04-02
> 기준: 코드 경로 점검 + 선택적 통합 테스트 실행 결과

---

## 1. 결론 요약

Arc Reactor에서 `RAG`와 `vector`는 완전히 같은 의미가 아니다.

- `RAG`는 주로 에이전트 실행 중 검색 컨텍스트를 시스템 프롬프트에 주입하는 경로를 뜻한다.
- `VectorStore`는 문서 저장/검색용 영속 벡터 저장소(PGVector 등)를 뜻한다.
- `EmbeddingModel`은 벡터화를 수행하지만, 항상 `VectorStore`를 쓰는 것은 아니다.

현재 상태를 한 줄로 정리하면 다음과 같다.

- 에이전트 RAG 검색 경로는 구현되어 있고, 조건부로 실제 실행된다.
- 스트리밍과 비스트리밍 모두 동일한 RAG 분류기를 사용한다.
- 문서 인제스트/검색 API는 `VectorStore`에 직접 연결된다.
- 사용자의 일반 Q&A는 기본적으로 자동 vector화되어 RAG에 들어가지 않는다.
- `rag.ingestion`을 켜면 사용자 Q&A를 "RAG 후보"로 저장할 수 있고, 기본 흐름은 관리자 승인 후 vector화/인제스트다.
- PGVector 자체 연동은 로컬 통합 테스트로 검증 가능했고 실제로 통과했다.
- 다만 "실제 Spring AI 임베딩 API 호출 + PGVectorStore 빈 + `/api/chat` 전체 E2E"는 이번 감사에서 실 API 키로 검증하지 않았다.
- 하이브리드 RAG(BM25 + vector)는 구현되어 있으나, 신규 문서의 BM25 인덱스 동기화는 런타임에서 자동 연결이 보이지 않는다.
- `rag.ingestion.enabled=true`를 JDBC 없이 켜는 경로는 안전하지 않다. 후보 저장소의 in-memory 기본 빈이 없다.
- `vector` 관련 기능 중 일부는 실제 `VectorStore`를 쓰지 않고 `EmbeddingModel`만 사용한다.

---

## 2. 이번에 직접 확인한 것

### 2.1 통과한 테스트

- `./gradlew :arc-core:test -PincludeIntegration --tests 'com.arc.reactor.integration.AgentRagIntegrationTest' --tests 'com.arc.reactor.rag.RagPipelineTest' --tests 'com.arc.reactor.rag.HybridRagPipelineTest' --tests 'com.arc.reactor.health.VectorStoreHealthIndicatorTest'`
- `./gradlew :arc-web:test -PincludeIntegration --tests 'com.arc.reactor.controller.DocumentControllerTest' --tests 'com.arc.reactor.integration.DocumentChunkingIntegrationTest' --tests 'com.arc.reactor.integration.RagIngestionIntegrationTest'`
- `./gradlew :arc-core:test -PincludeIntegration --tests 'com.arc.reactor.rag.integration.PgVectorRagIntegrationTest' --tests 'com.arc.reactor.rag.integration.RagIngestionIntegrationTest'`

### 2.2 확인된 검증 범위

- 에이전트가 RAG 컨텍스트를 시스템 프롬프트에 주입하는 경로
- RAG 실패 시 fail-open으로 일반 응답을 계속 생성하는 경로
- 문서 청킹 후 `VectorStore` 저장/검색/삭제 경로
- RAG ingestion 정책/후보 승인 흐름
- 로컬 PostgreSQL + pgvector 확장 연결, SQL 벡터 검색, 메타데이터 필터링

### 2.3 이번 감사에서 미검증인 것

- 실제 외부 임베딩 제공자(Gemini/OpenAI 등) API를 사용한 `/api/documents` 실환경 인제스트
- 실제 운영 프로파일에서 `PGVectorStore` 빈이 올라온 상태로 `/api/chat`이 RAG 검색을 수행하는 완전한 live E2E

즉, "DB/vector 연동"은 확인됐고, "실제 상용 임베딩 API까지 포함한 전체 온라인 경로"는 별도 실키 검증이 더 필요하다.

### 2.4 이번 감사 중 관찰한 비직결 실패 1건

광범위 웹 회귀 테스트 중 아래 1건은 실패했다.

- `ApiRegressionFlowIntegrationTest`

실패 내용은 tenant mismatch 400 응답의 에러 메시지 assertion이며, 이번 문서의 핵심 범위인 RAG/vector 경로 자체와는 직접 연결되지 않았다.
따라서 본 문서의 결론에는 반영하지 않았지만, 전체 회귀 안정성 관점에서는 별도로 정리할 가치가 있다.

---

## 3. 활성화 조건

### 3.1 에이전트 RAG

다음 조건이 모두 충족돼야 한다.

- `arc.reactor.rag.enabled=true`
- 런타임에 `VectorStore` 빈이 존재
- 요청이 `RagRelevanceClassifier` 기준으로 RAG 대상이어야 함

`RagConfiguration`은 `arc.reactor.rag.enabled=true`일 때만 활성화되며, `VectorStore`가 없으면 빈 초기화 단계에서 실패하도록 설계돼 있다.

### 3.2 PGVector 런타임

현재 프로젝트는 `pgvector` 관련 의존성이 항상 런타임에 들어오는 구조가 아니다.

- `:arc-app` runtimeClasspath 확인 결과:
  - 기본 실행: `spring-ai-starter-vector-store-pgvector` 없음
  - `-Pdb=true` 실행: `spring-ai-starter-vector-store-pgvector` 존재
- `Dockerfile`은 `ENABLE_DB=true`일 때 내부적으로 `-Pdb=true`로 빌드한다.
- `docker-compose.yml`의 `app` 서비스는 `ENABLE_DB=true`로 빌드하고, `db` 서비스는 `pgvector/pgvector:pg16` 이미지를 사용한다.

실무적으로는 다음처럼 보는 게 맞다.

- `docker-compose up` 또는 Docker 빌드 경로: PGVector 런타임 포함
- 단순 `./gradlew :arc-app:bootRun`: pgvector starter가 빠질 수 있으므로 RAG/vector 검증용 명령으로는 부족함

---

## 4. 어디서 RAG를 쓰는가

| 경로 | RAG 사용 | 설명 |
|------|----------|------|
| `AgentExecutionCoordinator` | 예 | 요청별로 RAG 필요 여부를 먼저 판정한 뒤, 필요하면 RAG 컨텍스트를 조회한다 |
| `StreamingExecutionCoordinator` | 예 | 스트리밍 경로도 동일한 분류기와 `RagContextRetriever`를 사용한다 |
| `RagContextRetriever` | 예 | `RagPipeline` 호출, topK 결정, 필터 추출, 타임아웃/에러 처리 담당 |
| `SystemPromptBuilder` | 예 | 검색된 컨텍스트를 `[Retrieved Context]` 블록으로 시스템 프롬프트에 주입 |
| `DefaultRagPipeline` | 예 | query transform → retrieve → rerank → compress → context build |
| `HybridRagPipeline` | 예 | vector + BM25 융합 검색 |
| `ParentDocumentRetriever` | 예 | 청크 검색 결과 주변 청크까지 확장 |
| `AdaptiveQueryRouter` | 예 | 복잡도에 따라 `topK`를 바꾸거나 retrieval 자체를 생략 |

핵심 진입 흐름은 아래 순서다.

1. `AgentExecutionCoordinator`가 `RagRelevanceClassifier.isRagRequired()`를 호출
2. true이면 `RagContextRetriever.retrieve()` 호출
3. `RagPipeline.retrieve()`가 검색 수행
4. 결과가 있으면 `SystemPromptBuilder`가 시스템 프롬프트에 주입

스트리밍도 본질적으로 동일하다.

1. `StreamingExecutionCoordinator`가 같은 `RagRelevanceClassifier`를 호출
2. 필요 시 같은 `RagContextRetriever`를 호출
3. 루프 시작 전 시스템 프롬프트에 컨텍스트를 포함한다

---

## 5. 어디서 RAG를 쓰지 않는가

다음 경로는 `rag.enabled=true`여도 RAG 검색을 생략할 수 있다.

### 5.1 프롬프트가 RAG 대상이 아닐 때

`RagRelevanceClassifier` 기준:

- `metadata.ragRequired=true`가 아니고
- `ragFilters` / `rag.filter.*`도 없고
- 프롬프트에 지식 질의 키워드가 없으면
- RAG 검색을 생략한다

반대로 다음 메타데이터는 RAG를 강제할 수 있다.

- `metadata.ragRequired=true`
- `metadata.ragFilters={...}`
- `metadata["rag.filter.<key>"]=...`

즉, 일반 키워드 분류를 통과하지 못해도 메타데이터로 retrieval을 강제할 수 있다.

### 5.2 워크스페이스 도구 라우팅이 우선될 때

다음 카테고리와 매칭되면 RAG보다 도구가 우선이다.

- `workContext`
- `work`
- `jira`
- `bitbucket`
- `swagger`
- `confluence`

즉, "Jira 보여줘", "Confluence 검색해줘" 같은 요청은 RAG 대신 도구 라우팅 경로로 간다.

### 5.3 문서 관리 API 자체

`/api/documents`는 RAG 검색을 하지 않는다. 이 API는 지식베이스를 넣고 찾는 관리 API다.

- 문서 추가: `VectorStore.add(...)`
- 문서 검색: `VectorStore.similaritySearch(...)`
- 문서 삭제: `VectorStore.delete(...)`

즉, `RAG retrieval`이 아니라 `RAG data plane`이다.

### 5.4 사용자 관점에서 "어떤 질문"이 RAG를 타는가

사용자 입장에서는 아래와 같은 "문서/지식 질의"가 RAG 대상이 될 가능성이 높다.

- "Arc Reactor에서 MCP 등록은 어떻게 해?"
- "사내 운영 가이드 기준으로 배포 절차 알려줘"
- "이 프로젝트에서 RAG ingestion이 어떻게 동작해?"
- "문서 기준으로 설명해줘"

반대로 아래처럼 일반 대화이거나, 별도 도구가 직접 처리해야 하는 요청은 보통 RAG를 타지 않는다.

- "안녕", "1+1은?", "오늘 기분 어때?"
- "Jira 이슈 보여줘"
- "Confluence에서 회의록 찾아줘"

즉, 이 프로젝트의 RAG는 "모든 대화에 자동 적용"이 아니라
"내부 문서/지식 검색이 필요하다고 분류된 요청"에만 붙는 조건부 기능이다.

---

## 6. 어디서 VectorStore를 쓰는가

| 경로 | VectorStore 사용 | 설명 |
|------|------------------|------|
| `RagConfiguration.documentRetriever()` | 예 | 에이전트 RAG 검색의 기본 dense retriever가 `VectorStore` 위에 올라간다 |
| `DocumentController` | 예 | 문서 추가/검색/삭제 API |
| `RagIngestionCandidateController` | 예 | 승인된 후보를 실제 벡터 저장소에 인제스트 |
| `RagIngestionCaptureHook` | 조건부 | `requireReview=false`이고 `VectorStore`가 있으면 자동 인제스트 |
| `VectorStoreHealthIndicator` | 예 | 헬스 체크에서 VectorStore 접근성 확인 |
| `PlatformAdminController /vectorstore/stats` | 아니오 | 실제 조회가 아니라 `vectorStore != null`만 확인한다 |
| `Bm25WarmUpRunner` | 예 | 시작 시 VectorStore 내용을 읽어 BM25 인덱스 warm-up |

---

## 7. 사용자 질문/답변(Q&A)은 언제 vector화되는가

이 부분은 운영 문서에서 가장 헷갈리기 쉬운 부분이다.

### 7.1 기본값: 자동 vector화하지 않음

사용자가 Reactor에 질문하고 답변을 받았다고 해서,
그 질문/답변 자체가 자동으로 즉시 `VectorStore`에 들어가지는 않는다.

즉, 기본적으로는 다음과 같이 동작한다.

1. 사용자가 질문한다
2. 필요하면 RAG로 기존 문서를 검색한다
3. Reactor가 답변한다
4. 그 Q&A가 자동으로 새 지식으로 적재되지는 않는다

따라서 "대화 이력 보존"과 "RAG 지식베이스 적재"는 다른 개념으로 봐야 한다.

### 7.2 옵션 기능: RAG ingestion 후보 저장

`arc.reactor.rag.ingestion.enabled=true`이면,
성공적인 사용자 Q&A를 `RAG ingestion candidate`로 저장할 수 있다.

이때도 기본 흐름은 곧바로 vector화가 아니라 아래와 같다.

1. 사용자 질문 + Reactor 응답 발생
2. `RagIngestionCaptureHook`가 정책 조건을 통과한 Q&A를 후보로 캡처
3. 후보는 `PENDING` 상태로 저장
4. 관리자가 후보를 검토
5. 승인 시에만 문서화 + vector화 + `VectorStore.add(...)`

즉, 이 기능은 "자동 학습"보다는
"운영자가 검토 가능한 Q&A 기반 지식 수집 파이프라인"에 가깝다.

### 7.3 관리자 승인 시 실제로 vector화됨

관리자가 후보를 승인하면 다음 순서로 실제 인제스트가 일어난다.

1. 후보 Q&A를 문서 형태로 변환
2. 필요하면 청킹 수행
3. `VectorStore.add(documents)` 호출
4. 이 시점에 저장소 구현체가 임베딩/벡터화를 수행
5. 이후부터 해당 내용이 RAG 검색 대상이 됨

즉, "후보 저장"과 "실제 RAG 반영"은 같은 단계가 아니다.
실제 반영은 승인 시점이다.

### 7.4 예외: 관리자 승인 없이 자동 인제스트 가능

정책에서 `requireReview=false`이고 `VectorStore`가 존재하면,
후보 저장 후 관리자 승인을 기다리지 않고 바로 인제스트할 수 있다.

하지만 코드 의도의 기본값은 수동 검토 흐름이다.
따라서 운영 문서에는 다음처럼 적는 것이 정확하다.

- 기본: 후보 저장 -> 관리자 승인 -> vector화 -> RAG 반영
- 예외: `requireReview=false`면 자동 vector화 가능

### 7.5 직접 문서 업로드와의 차이

RAG 지식베이스를 채우는 가장 명시적인 방법은 `/api/documents`다.

- `/api/documents`: 관리자가 직접 문서를 넣는다
- `rag.ingestion`: 사용자 Q&A를 후보로 수집한 뒤, 승인된 것만 문서처럼 넣는다

둘 다 최종적으로는 `VectorStore.add(...)`로 들어가지만,
데이터의 출처와 운영 통제 방식이 다르다.

---

## 8. Vector는 쓰지만 RAG는 아닌 경로

여기서 `vector`는 정확히는 `embedding` 기반 기능이다.

### 8.1 시맨틱 응답 캐시

`RedisSemanticResponseCache`는 다음을 사용한다.

- `EmbeddingModel`
- `Redis`

하지만 다음은 사용하지 않는다.

- `RagPipeline`
- `VectorStore`

즉, 질문 의미가 비슷하면 응답 캐시를 재활용하는 기능이지, RAG 검색 기능이 아니다.

### 8.2 시맨틱 도구 선택

`SemanticToolSelector`는 다음을 사용한다.

- 사용자 프롬프트 임베딩
- 도구 설명 임베딩
- 코사인 유사도

하지만 다음은 사용하지 않는다.

- `VectorStore`
- `RagPipeline`

즉, "어떤 도구를 보여줄지" 고르는 기능이지, 문서를 검색하는 기능이 아니다.

### 8.3 관리 화면의 vector 통계 API

`/api/admin/vectorstore/stats`는 이름과 달리 실제 vector 검색이나 임베딩을 수행하지 않는다.

- `VectorStore` 빈을 주입
- `vectorStore != null` 여부만 응답

즉, 이 API는 health/statistics라기보다 bean presence 점검에 가깝다.

---

## 8.4 분류표: EmbeddingModel only vs VectorStore

| 기능 | EmbeddingModel | VectorStore | 비고 |
|------|----------------|-------------|------|
| Semantic response cache | 예 | 아니오 | 의미 유사 응답 캐시 |
| Semantic tool selection | 예 | 아니오 | 도구 설명/프롬프트 코사인 유사도 |
| `/api/documents` | 직접 확인 불가 | 예 | 저장소 구현 내부에서 임베딩할 수는 있으나, repo 코드상 직접 호출은 아님 |
| `/api/rag-ingestion/candidates/{id}/approve` | 직접 확인 불가 | 예 | 승인 시 `VectorStore.add(...)` |
| `RagIngestionCaptureHook` 자동 수집 | 직접 확인 불가 | 예 | auto-ingest 조건부 |
| `VectorStoreHealthIndicator` | 아니오 | 예 | probe only |
| `Bm25WarmUpRunner` | 아니오 | 예 | startup maintenance |
| `/api/admin/vectorstore/stats` | 아니오 | 아니오 | bean presence만 확인 |

---

## 9. 아예 RAG / Vector와 무관한 경로

다음은 기본적으로 RAG/vector와 직접 연결되지 않는다.

- Guard 파이프라인
- 일반 Hook 시스템(단, `RagIngestionCaptureHook` 제외)
- MemoryStore / ConversationManager
- Output Guard
- Approval / Tool Policy
- Persona / Prompt Template
- MCP 서버 등록 및 프록시

이 경로들은 독립적으로 동작하며, `rag.enabled=false`여도 계속 사용 가능하다.

---

## 10. 현재 구현상 주의할 점

### 10.1 하이브리드 RAG의 BM25 신선도 문제

`HybridRagPipeline`는 BM25 인덱싱 메서드(`indexDocument`, `indexDocuments`)를 제공하고,
`Bm25WarmUpRunner`는 시작 시 VectorStore를 읽어 한 번 warm-up 한다.

하지만 문서가 런타임에 새로 추가될 때 다음 연결은 보이지 않는다.

- `/api/documents` 추가 후 BM25 자동 인덱싱
- RAG ingestion 승인 후 BM25 자동 인덱싱

따라서 현재 하이브리드 검색은 다음처럼 보는 것이 안전하다.

- 서비스 시작 시점에 있던 문서: BM25 + vector 융합 가능
- 시작 후 새로 들어온 문서: vector 검색은 되지만 BM25 반영은 재시작 전까지 누락될 수 있음

### 10.2 RAG ingestion candidate store 기본 빈 부재

코드상 `InMemoryRagIngestionCandidateStore` 구현은 존재한다.
하지만 자동 구성에서 기본 빈으로 등록되는 경로는 보이지 않고, JDBC 저장소만 조건부 등록된다.

따라서 `arc.reactor.rag.ingestion.enabled=true`를 JDBC 없이 켜면 다음 위험이 있다.

- `RagIngestionCaptureHook`
- `RagIngestionCandidateController`

가 요구하는 `RagIngestionCandidateStore` 빈이 없어 시작 실패 또는 구성 오류가 날 수 있다.

이 부분은 문서화뿐 아니라 실제 자동구성 보강이 필요한 영역이다.

### 10.3 로컬 실행 문서와 실제 pgvector classpath의 차이

단순 `./gradlew :arc-app:bootRun`은 pgvector starter를 보장하지 않는다.

RAG/vector 실검증용 로컬 실행은 최소한 다음이 더 안전하다.

```bash
./gradlew :arc-app:bootRun -Pdb=true
```

또는

```bash
docker-compose up -d
```

### 10.4 `rag.enabled=true`가 곧 “모든 chat이 RAG 사용”을 의미하지는 않음

이 프로젝트의 chat 경로는 기본적으로 보수적이다.

- RAG 분류기를 먼저 통과해야 한다
- 워크스페이스 도구 라우트와 충돌하면 RAG를 생략한다
- adaptive routing이 `NO_RETRIEVAL`을 반환해도 생략한다
- timeout / exception / empty result이면 fail-open으로 일반 응답을 계속 생성한다

즉, 운영 관점에서 `rag.enabled=true`는 “RAG 가능 상태”이지 “모든 요청에서 RAG 강제 사용”이 아니다.

---

## 11. 기능별 분류표

| 기능 | RAG | VectorStore | EmbeddingModel | 현재 판단 |
|------|-----|-------------|----------------|----------|
| `/api/chat`, `/api/chat/stream`의 문서 검색 주입 | 조건부 사용 | 조건부 사용 | 조건부 사용 | 구현 및 테스트 확인 |
| `/api/chat/multipart` | 조건부 사용 | 조건부 사용 | 조건부 사용 | 일반 chat과 동일 executor 경로 |
| `/api/documents` | 미사용 | 사용 | 사용 | 구현 및 테스트 확인 |
| RAG ingestion 후보 캡처/승인 | 미사용 | 조건부 사용 | 조건부 사용 | 구현 및 테스트 확인, candidate store 주의 |
| 사용자 Q&A의 자동 RAG 반영 | 미사용 | 기본적으로 미사용 | 기본적으로 미사용 | 기본값은 후보 저장 또는 미적재, 자동 학습 아님 |
| Hybrid RAG | 사용 | 사용 | 사용 | 구현 확인, BM25 live sync 공백 |
| Semantic response cache | 미사용 | 미사용 | 사용 | RAG 아님, `EmbeddingModel only` |
| Semantic tool selection | 미사용 | 미사용 | 사용 | RAG 아님, `EmbeddingModel only` |
| `/api/admin/vectorstore/stats` | 미사용 | 미사용 | 미사용 | bean presence만 확인 |
| Memory / Guard / Output Guard | 미사용 | 미사용 | 미사용 | RAG/vector와 독립 |

---

## 12. 관련 기능 설명 카드

### 12.1 DocumentChunker

- 역할: 긴 문서를 여러 청크로 쪼개어 검색 품질과 저장 효율을 높인다.
- 사용처: `/api/documents`, RAG ingestion 승인, 자동 인제스트
- 동작: 원문 문서를 청크 문서 여러 개로 변환한 뒤 `VectorStore.add(...)`
- 운영 포인트: 삭제 시에도 부모 문서 ID만이 아니라 파생 청크 ID 정리가 함께 필요하다.

### 12.2 QueryTransformer

- 역할: 검색 전 사용자 질의를 RAG 친화적으로 바꾼다.
- 모드:
  - `passthrough`: 변환 없이 그대로 검색
  - `hyde`: 가상 문서를 만들어 검색 품질을 높이려는 방식
  - `decomposition`: 복잡한 질문을 여러 하위 질문으로 나누는 방식
- 운영 포인트: 검색 품질을 높일 수 있지만, LLM 호출이 늘어 비용/지연이 증가할 수 있다.

### 12.3 AdaptiveQueryRouter

- 역할: 요청이 `NO_RETRIEVAL`, `SIMPLE`, `COMPLEX` 중 어디에 가까운지 판정한다.
- 영향:
  - `NO_RETRIEVAL`: RAG 생략 가능
  - `SIMPLE`: 기본 검색
  - `COMPLEX`: 더 공격적인 검색 또는 더 큰 `topK`
- 운영 포인트: `rag.enabled=true`여도 모든 요청이 retrieval까지 가는 것은 아니다.

### 12.4 ParentDocumentRetriever

- 역할: 검색된 청크 주변의 이웃 청크까지 함께 가져와 문맥을 넓힌다.
- 장점: 청크 단위 검색에서 문장이 잘려 의미가 끊기는 문제를 줄인다.
- 운영 포인트: 문맥 품질은 좋아질 수 있지만, 주입 토큰 수는 늘어난다.

### 12.5 ContextCompressor

- 역할: 검색된 문서를 그대로 다 넣지 않고, 필요한 내용만 압축해서 시스템 프롬프트로 넘긴다.
- 장점: 컨텍스트 토큰 비용을 줄이고 잡음을 줄인다.
- 운영 포인트: 과도한 압축은 필요한 근거를 잃게 만들 수 있다.

### 12.6 RAG Ingestion Policy

- 역할: 어떤 사용자 Q&A를 후보로 캡처할지 정책으로 제어한다.
- 제어 항목 예:
  - enabled
  - requireReview
  - 최소 질문 길이 / 최소 답변 길이
  - 허용 채널
  - 차단 패턴
- 운영 포인트: 이 정책이 "자동 학습처럼 보이지만 실제로는 선별 수집"이라는 동작을 결정한다.

### 12.7 Multipart Chat

- 역할: 멀티파트 요청으로 텍스트와 파일을 함께 보내는 chat 엔드포인트다.
- RAG 관점: 별도 RAG 시스템이 아니라 일반 chat executor 경로를 탄다.
- 운영 포인트: multipart라고 해서 자동으로 문서가 지식베이스에 적재되지는 않는다.

### 12.8 Semantic Response Cache

- 역할: 의미적으로 비슷한 질문이면 이전 응답을 재사용한다.
- 사용 자원: `EmbeddingModel` + `Redis`
- 비고: 문서 검색이 아니라 캐시 재활용이므로 RAG와 다르다.

### 12.9 Semantic Tool Selection

- 역할: 어떤 도구를 보여주거나 우선 선택할지를 임베딩 유사도로 판단한다.
- 사용 자원: 프롬프트 임베딩, 도구 설명 임베딩
- 비고: 문서 검색이 아니라 도구 라우팅이므로 RAG와 다르다.

### 12.10 VectorStore Health / Stats

- `VectorStoreHealthIndicator`
  - 역할: 실제 `similaritySearch(...)` probe로 접근 가능성을 확인한다.
- `/api/admin/vectorstore/stats`
  - 역할: 이름과 달리 실제 통계가 아니라 `VectorStore` 빈 존재 여부에 가깝다.
- 운영 포인트: stats API가 정상이라고 해서 실제 벡터 검색 품질까지 보장되지는 않는다.

### 12.11 BM25 Warm-Up

- 역할: Hybrid RAG 사용 시 시작 시점 문서를 읽어 BM25 인덱스를 미리 채운다.
- 장점: 재시작 직후 하이브리드 검색이 바로 동작하기 쉬워진다.
- 운영 포인트: 신규 문서 증분 반영은 현재 별도 확인이 필요하다.

---

## 13. 운영 설명용 문구

팀 문서나 운영 가이드에 바로 옮기기 쉬운 문장으로 정리하면 아래가 가장 정확하다.

> Arc Reactor의 RAG는 모든 대화에 자동 적용되지 않는다. 내부 문서/지식 검색이 필요하다고 분류된 요청에만 조건부로 적용된다.

> 사용자의 질문/답변은 기본적으로 자동 vector화되어 RAG에 쌓이지 않는다. 다만 `rag.ingestion` 기능을 켜면 Q&A를 수집 후보로 저장할 수 있고, 기본 흐름은 관리자 승인 후 vector화하여 RAG에 반영하는 방식이다.

> 직접 문서를 넣는 경로와 Q&A 기반 수집 경로는 다르지만, 최종 반영 시점에는 둘 다 `VectorStore.add(...)`를 통해 벡터 저장소에 적재된다.

---

## 14. 지금 기준의 운영 판단

운영 문장으로 정리하면 다음이 맞다.

- "Arc Reactor는 RAG를 지원한다"는 말은 맞다.
- 다만 모든 채팅 요청이 항상 RAG를 쓰는 구조는 아니다.
- "Arc Reactor는 vector를 쓴다"도 맞다.
- 다만 vector 사용은 `VectorStore 기반 RAG/문서 API`와 `Embedding 기반 시맨틱 기능`으로 나뉜다.
- "로컬에서 바로 RAG가 붙는다"는 말은 불완전하다.
- pgvector starter와 실제 `VectorStore` 빈이 올라오는 실행 경로를 명시해야 한다.

현재 팀 문서에는 아래처럼 적는 것이 가장 정확하다.

> Arc Reactor의 RAG는 조건부 기능이다. `arc.reactor.rag.enabled=true`와 `VectorStore` 빈이 모두 필요하며, 실제 요청도 RAG 분류 기준을 통과해야 검색이 수행된다. Vector 관련 기능은 RAG 외에도 시맨틱 캐시와 시맨틱 도구 선택에 사용되지만, 이 둘은 `VectorStore`가 아니라 `EmbeddingModel` 기반 기능이다.

---

## 15. 근거 파일 맵

### 15.1 에이전트 RAG 경로

- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/AgentExecutionCoordinator.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/StreamingExecutionCoordinator.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/RagRelevanceClassifier.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/RagContextRetriever.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/SystemPromptBuilder.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorRagConfiguration.kt`

### 15.2 VectorStore / 문서 API / ingestion 경로

- `arc-web/src/main/kotlin/com/arc/reactor/controller/DocumentController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/RagIngestionCandidateController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/RagIngestionPolicyController.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/hook/impl/RagIngestionCaptureHook.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorHookAndMcpConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorPreflightConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorJdbcStoreConfigurations.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/rag/chunking/TokenBasedDocumentChunker.kt`

### 15.3 비-RAG embedding/vector 기능

- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorSemanticCacheConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/cache/impl/RedisSemanticResponseCache.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorCoreBeansConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/tool/SemanticToolSelector.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/health/VectorStoreHealthIndicator.kt`
- `arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/PlatformAdminController.kt`

### 15.4 빌드 / 배포 / 런타임 classpath

- `arc-core/build.gradle.kts`
- `arc-app/build.gradle.kts`
- `Dockerfile`
- `docker-compose.yml`
- `helm/arc-reactor/values.yaml`
- `helm/arc-reactor/templates/configmap.yaml`

### 15.5 이번 감사에서 실행한 대표 테스트

- `arc-core/src/test/kotlin/com/arc/reactor/integration/AgentRagIntegrationTest.kt`
- `arc-core/src/test/kotlin/com/arc/reactor/rag/integration/PgVectorRagIntegrationTest.kt`
- `arc-core/src/test/kotlin/com/arc/reactor/rag/integration/RagIngestionIntegrationTest.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/controller/DocumentControllerTest.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/integration/DocumentChunkingIntegrationTest.kt`
- `arc-web/src/test/kotlin/com/arc/reactor/integration/RagIngestionIntegrationTest.kt`
