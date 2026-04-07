# Arc Reactor 프로덕션 인프라 스펙

## 개요

Arc Reactor는 Spring Boot 기반 AI Agent 서버로, 300명+ 직원이 Slack/Web에서 동시 사용한다.
외부 LLM API(Gemini/OpenAI)를 호출하므로 GPU는 불필요하며, CPU/메모리/네트워크가 핵심.

## 서버 구성

### 최소 구성 (단일 서버)

Arc Reactor + PostgreSQL + Redis를 한 서버에 구동.
300명 규모에서 동시 접속 10~30명 수준이면 충분.

CPU: 4코어 이상 (vCPU 기준)
메모리: 16GB (JVM 4GB + PostgreSQL 4GB + Redis 2GB + OS/여유 6GB)
디스크: 100GB SSD (DB 데이터 + 로그)
네트워크: 외부 API 호출용 아웃바운드 HTTPS 필요 (Gemini API, Slack API, Atlassian API)

### 권장 구성 (분리 배포)

서비스 안정성과 확장성을 고려한 구성.

애플리케이션 서버: 2코어 / 8GB RAM / 50GB SSD
데이터베이스 서버: 2코어 / 8GB RAM / 100GB SSD (PostgreSQL 15+, pgvector 확장)
Redis: 1코어 / 2GB RAM (캐시 + 세션, 별도 또는 매니지드)

### 클라우드 환경 참고

AWS 기준: t3.xlarge (4vCPU, 16GB) 또는 m6i.large (2vCPU, 8GB) × 2
NCP 기준: Standard s2-g3 (4vCPU, 16GB) 동급
사내 VM: 동일 스펙

## 필수 소프트웨어

JDK 21 (Eclipse Temurin 또는 Amazon Corretto)
PostgreSQL 15+ (pgvector 확장 필수 — RAG 벡터 검색용)
Redis 7+ (캐시, 런타임 설정, 세션)
Docker (선택 — PostgreSQL/Redis 컨테이너 실행용)

## 네트워크 요구사항

아웃바운드 HTTPS (443): Gemini API (generativelanguage.googleapis.com), Slack API (slack.com, wss://wss-primary.slack.com), Atlassian API (api.atlassian.com)
인바운드: 8080 포트 (HTTP, 내부 네트워크만 허용)
Slack Socket Mode 사용 시 인바운드 웹훅 불필요 (WebSocket 아웃바운드만)

## 환경변수 (최소 필수)

SPRING_DATASOURCE_URL=jdbc:postgresql://{DB_HOST}:5432/arcreactor
SPRING_DATASOURCE_USERNAME={DB_USER}
SPRING_DATASOURCE_PASSWORD={DB_PASSWORD}
SPRING_AI_GOOGLE_GENAI_API_KEY={GEMINI_API_KEY}
ARC_REACTOR_AUTH_JWT_SECRET={32자 이상 시크릿}
ARC_REACTOR_AUTH_ADMIN_EMAIL={초기 관리자 이메일}
ARC_REACTOR_AUTH_ADMIN_PASSWORD={초기 관리자 비밀번호}
SLACK_BOT_TOKEN={xoxb-...}
SLACK_APP_TOKEN={xapp-...}
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_ADMIN_ENABLED=true
ARC_REACTOR_ADMIN_PRIVACY_STORE_SESSION_IDENTIFIERS=true

전체 환경변수 목록: docs/ko/operations/environment-variables.md

## 예상 리소스 사용량

일일 LLM API 호출: 300명 × 평균 5회 = 1,500건/일
일일 토큰 소비: ~300만 토큰 (Gemini Flash 기준 ~$0.30/일)
월간 비용: LLM API ~$10, 서버 인프라 별도
DB 용량: 월 ~500MB 증가 (대화 이력 + 메트릭)
동시 접속 처리: 기본 20 동시 요청 (설정으로 조정 가능)

## 배포 방법

Docker Compose (권장):
arc-reactor 레포지토리에 docker-compose.yml 포함.
docker compose up -d 로 전체 스택 기동.

JAR 직접 실행:
./gradlew :arc-app:bootJar 빌드 후 java -jar arc-app.jar 실행.
PostgreSQL, Redis는 별도 설치 필요.

## 모니터링

Spring Boot Actuator: /actuator/health (헬스체크), /actuator/metrics (메트릭)
Admin 대시보드: http://{서버}:3001 (arc-reactor-admin)
Swagger UI: http://{서버}:8080/swagger-ui.html (API 문서)

## 보안 고려사항

JWT 기반 인증 (HS384)
7단계 Input Guard 파이프라인 (인젝션 방어, 속도 제한 등)
Output Guard (PII 마스킹, 시스템 프롬프트 유출 방지)
Admin API는 ADMIN 역할만 접근 가능
Slack 토큰은 환경변수로만 관리 (코드/DB에 미저장)
