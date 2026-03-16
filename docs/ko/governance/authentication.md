# JWT 인증 시스템

## 한 줄 요약

**JWT 인증은 런타임 필수.** 기업은 `AuthProvider` 인터페이스만 구현하면 LDAP/SSO 등 커스텀 인증으로 교체 가능.

---

## 런타임 필수 조건

런타임 환경에서는 인증이 항상 활성화된다:

- `arc.reactor.auth.jwt-secret`에 32바이트 이상 시크릿 설정
- 인증 설정이 없거나 잘못되면 애플리케이션 기동 실패

---

## 아키텍처

```
요청
  |
  v
+------------------------------------------------------+
|  AuthRateLimitFilter (HIGHEST_PRECEDENCE + 1)        |
|                                                       |
|  POST /api/auth/login, /api/auth/register             |
|  -> 제한 이내: 통과                                    |
|  -> 제한 초과: 429 Too Many Requests                  |
+------------------------------------------------------+
  |
  v
+------------------------------------------------------+
|  JwtAuthWebFilter (HIGHEST_PRECEDENCE + 2)           |
|                                                       |
|  /api/auth/login (+ /api/auth/register when enabled)  |
|  -> 통과 (public)                                     |
|  그 외 -> Authorization: Bearer <token> 검증          |
|       -> 유효: exchange.attributes["userId"] = userId |
|       -> 무효: 401 Unauthorized 응답                  |
+------------------------------------------------------+
  |
  v
+------------------------------------------------------+
|  Controllers (ChatController, SessionController 등)  |
|                                                       |
|  exchange.attributes["userId"]로 인증된 사용자 식별   |
|  -> Guard rate limit 키, 세션 격리, 소유권 확인       |
+------------------------------------------------------+
```

### 핵심 설계 결정

| 결정 | 이유 |
|------|------|
| Spring Security 없음 | 경량, 프레임워크 의존성 최소화 |
| WebFilter (not Interceptor) | WebFlux reactive 호환 |
| `ServerWebExchange.attributes` | ThreadLocal이 아닌 reactive-safe 방식 |
| JJWT + spring-security-crypto만 | Spring Security 전체가 아닌 라이브러리만 사용 |
| Auth 의존성 기본 포함 | 실행 시 Bean 누락/혼선 방지 |

---

## 설정

### application.yml

```yaml
arc:
  reactor:
    auth:
      jwt-secret: ${JWT_SECRET}              # HS256 서명 시크릿 (32바이트 이상 권장)
      jwt-expiration-ms: 86400000            # 토큰 유효기간 (기본: 24시간)
      self-registration-enabled: false       # 기본값: 셀프 회원가입 비활성 (권장)
      login-rate-limit-per-minute: 10        # IP당 분당 최대 인증 실패 횟수
      trust-forwarded-headers: false         # IP 추출 시 X-Forwarded-For 신뢰 여부 (프록시 환경)
      token-revocation-store: memory         # 폐기 토큰 백엔드: memory | jdbc | redis
      public-paths:                          # 인증 없이 접근 가능한 경로
        - /api/auth/login
```

### 빌드 (build.gradle.kts)

```bash
# 인증은 런타임 필수이며 기본 포함
./gradlew :arc-app:bootRun

# DB 지원 포함 bootJar 빌드
./gradlew :arc-app:bootJar -Pdb=true
```

### Docker

```dockerfile
# 인증은 기본 포함, ARG는 DB 패키징만 제어
ARG ENABLE_DB=true
RUN GRADLE_ARGS=":arc-app:bootJar"; \
    if [ "$ENABLE_DB" = "true" ]; then GRADLE_ARGS="$GRADLE_ARGS -Pdb=true"; fi; \
    ./gradlew $GRADLE_ARGS
```

```yaml
# docker-compose.yml
services:
  app:
    environment:
      ARC_REACTOR_AUTH_JWT_SECRET: "your-256-bit-secret-here-minimum-32-bytes"
```

---

## API 엔드포인트

`AuthController`는 항상 등록된다.

발급되는 JWT에는 `tenantId` 클레임이 포함되며 기본값은 `default`이다
(`arc.reactor.auth.default-tenant-id`로 변경 가능).

### POST /api/auth/register -- 회원가입

`arc.reactor.auth.self-registration-enabled=true`일 때만 활성화된다 (기본값: `false`).

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123",
    "name": "User Name"
  }'
```

**응답 (201 Created):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "name": "User Name"
  }
}
```

**에러 (409 Conflict):**
```json
{
  "token": "",
  "user": null,
  "error": "Email already registered"
}
```

**에러 (403 Forbidden, 기본 동작):**
```json
{
  "token": "",
  "user": null,
  "error": "Self-registration is disabled. Contact an administrator."
}
```

**검증:**
- `email`: 유효한 이메일 형식 (`@Email`)
- `password`: 8자 이상 (`@Size(min=8)`)
- `name`: 빈 값 불가 (`@NotBlank`)

### POST /api/auth/login -- 로그인

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123"
  }'
```

**응답 (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "name": "User Name"
  }
}
```

**에러 (401 Unauthorized):**
```json
{
  "token": "",
  "user": null,
  "error": "Invalid email or password"
}
```

### GET /api/auth/me -- 현재 사용자 조회

```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

**응답 (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "name": "User Name"
}
```

### POST /api/auth/change-password -- 비밀번호 변경

```bash
curl -X POST http://localhost:8080/api/auth/change-password \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "currentPassword": "password123",
    "newPassword": "newpassword456"
  }'
```

### POST /api/auth/logout -- 현재 토큰 폐기

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

---

## 컴포넌트 구조

### 인터페이스

| 인터페이스 | 역할 | 기본 구현 |
|-----------|------|---------|
| `AuthProvider` | 인증 로직 (authenticate, getUserById) | `DefaultAuthProvider` (BCrypt) |
| `UserStore` | 사용자 CRUD (findByEmail, save, update) | `InMemoryUserStore` / `JdbcUserStore` |
| `TokenRevocationStore` | 폐기된 JWT `jti` 값을 원래 만료 시점까지 추적 | `InMemoryTokenRevocationStore` / `JdbcTokenRevocationStore` / `RedisTokenRevocationStore` |

### 클래스

| 클래스 | 역할 |
|-------|------|
| `AuthModels.kt` | `User`, `UserRole`, `AdminScope`, `AuthProperties`, `TokenRevocationStoreType` |
| `JwtTokenProvider` | JJWT로 토큰 생성 (`createToken`) / 검증 (`validateToken`) |
| `JwtAuthWebFilter` | WebFilter -- 모든 요청에서 Bearer 토큰 검증 |
| `AuthRateLimitFilter` | WebFilter -- `/api/auth/login` 및 `/api/auth/register`에 대한 POST 요청 속도 제한 |
| `AdminInitializer` | 환경 변수로 서버 기동 시 초기 ADMIN 계정 생성 |
| `AdminAuthorizationSupport` | 공유 admin 인가 헬퍼 (`isAdmin`, `isAnyAdmin`) |
| `DefaultAuthProvider` | BCrypt 비밀번호 검증 기본 구현 |
| `InMemoryUserStore` | ConcurrentHashMap 기반 (개발용) |
| `JdbcUserStore` | PostgreSQL `users` 테이블 (프로덕션용) |
| `AuthController` | REST 엔드포인트 (register, login, me, change-password, logout) |

### 자동 구성 빈

모든 auth 빈은 auth 의존성이 classpath에 있으면 항상 등록된다.

```
arc.reactor.auth.jwt-secret 설정
  |
  +-- AuthProperties
  +-- JwtTokenProvider
  +-- AuthRateLimitFilter (WebFilter, HIGHEST_PRECEDENCE + 1)
  +-- JwtAuthWebFilter (WebFilter, HIGHEST_PRECEDENCE + 2)
  +-- UserStore
  |     +-- DataSource 있으면 -> JdbcUserStore (@Primary)
  |     +-- DataSource 없으면 -> InMemoryUserStore
  +-- AuthProvider
  |     +-- @ConditionalOnMissingBean -> DefaultAuthProvider
  +-- TokenRevocationStore
  |     +-- memory (기본) -> InMemoryTokenRevocationStore
  |     +-- jdbc -> JdbcTokenRevocationStore (폴백: in-memory)
  |     +-- redis -> RedisTokenRevocationStore (폴백: in-memory)
  +-- AdminInitializer (기동 시 admin 계정 생성)
  +-- AuthController (@RestController)
```

---

## JWT 토큰 구조

```
Header:  { "alg": "HS256" }
Payload: {
  "jti": "d4f0...",                // 토큰 ID (폐기 키)
  "sub": "550e8400-...",          // userId
  "email": "user@example.com",    // 사용자 이메일
  "role": "ADMIN",                // 사용자 역할
  "tenantId": "default",          // 테넌트 격리 키
  "iat": 1707350400,              // 발급 시각
  "exp": 1707436800               // 만료 시각 (24시간 후)
}
Signature: HMACSHA256(header + payload, jwt-secret)
```

---

## 커스텀 인증 구현

### AuthProvider 교체 (LDAP, SSO 등)

```kotlin
@Component
class LdapAuthProvider(private val ldapTemplate: LdapTemplate) : AuthProvider {

    override fun authenticate(email: String, password: String): User? {
        // LDAP 인증 로직
        val ldapUser = ldapTemplate.authenticate(email, password)
            ?: return null
        return User(
            id = ldapUser.uid,
            email = ldapUser.email,
            name = ldapUser.displayName,
            passwordHash = "" // LDAP이 관리하므로 비어있음
        )
    }

    override fun getUserById(userId: String): User? {
        // LDAP에서 사용자 조회
        return ldapTemplate.findByUid(userId)?.toUser()
    }
}
```

`@Component`로 등록하면 `@ConditionalOnMissingBean`에 의해 `DefaultAuthProvider`가 자동으로 비활성화된다.

### UserStore 교체 (Redis 등)

```kotlin
@Component
class RedisUserStore(private val redisTemplate: RedisTemplate<String, User>) : UserStore {
    override fun findByEmail(email: String): User? { /* Redis 조회 */ }
    override fun findById(id: String): User? { /* Redis 조회 */ }
    override fun save(user: User): User { /* Redis 저장 */ }
    override fun existsByEmail(email: String): Boolean { /* Redis 존재 확인 */ }
}
```

---

## 사용자 역할 및 Admin 범위

### UserRole

각 사용자에게는 데이터베이스에 저장되고 JWT 토큰에 포함되는 `role` 필드가 있다. 역할은 admin 엔드포인트 및 대시보드에 대한 접근을 제어한다.

| 역할 | 설명 | `isAnyAdmin()` | `isDeveloperAdmin()` |
|------|------|:---:|:---:|
| `USER` | 표준 접근 (채팅, 페르소나 선택) | 아니오 | 아니오 |
| `ADMIN` | 전체 admin 접근 (레거시 역할, 모든 admin 권한 부여) | 예 | 예 |
| `ADMIN_MANAGER` | 매니저 전용 대시보드만 | 예 | 아니오 |
| `ADMIN_DEVELOPER` | 개발자/admin 제어 화면 | 예 | 예 |

### AdminScope

`AdminScope`는 프론트엔드 워크스페이스 결정을 위해 `UserRole`에서 파생된 대분류 enum이다. 각 admin 역할은 하나의 scope에 매핑된다:

| AdminScope | 매핑 원본 UserRole | 용도 |
|------------|-------------------|------|
| `FULL` | `ADMIN` | 무제한 admin 접근 |
| `MANAGER` | `ADMIN_MANAGER` | 매니저 대시보드 (사용량 분석, 빌링) |
| `DEVELOPER` | `ADMIN_DEVELOPER` | 개발자 제어 화면 (도구 설정, 프롬프트, MCP) |

`USER` 역할은 `AdminScope`가 없다 (`null` 반환).

### AdminAuthorizationSupport

컨트롤러의 admin 인가는 반드시 `AdminAuthorizationSupport`를 사용해야 한다:

- `AdminAuthorizationSupport.isAdmin(exchange)` -- 개발자 수준 admin 접근 확인 (`ADMIN` 또는 `ADMIN_DEVELOPER`)
- `AdminAuthorizationSupport.isAnyAdmin(exchange)` -- 모든 admin 역할 확인 (`ADMIN`, `ADMIN_MANAGER`, 또는 `ADMIN_DEVELOPER`)

null 역할은 비-admin으로 처리된다 (fail-close). 이 로직을 인라인으로 복제하지 말 것.

---

## Admin 초기화

`AdminInitializer`는 환경 변수를 사용하여 서버 기동 시 초기 ADMIN 계정을 생성한다. `ApplicationReadyEvent`에서 한 번 실행된다.

### 환경 변수

| 변수 | 필수 | 설명 |
|------|------|------|
| `ARC_REACTOR_AUTH_ADMIN_EMAIL` | 예 | Admin 계정 이메일 |
| `ARC_REACTOR_AUTH_ADMIN_PASSWORD` | 예 | Admin 계정 비밀번호 (최소 8자) |
| `ARC_REACTOR_AUTH_ADMIN_NAME` | 아니오 | 표시 이름 (기본값: `"Admin"`) |

### 동작

1. 환경 변수가 설정되지 않으면 초기화를 건너뛴다.
2. 비밀번호가 8자 미만이면 경고와 함께 초기화를 건너뛴다.
3. 해당 이메일의 사용자가 이미 존재하면 초기화를 건너뛴다 (멱등성).
4. 커스텀 `AuthProvider` (예: LDAP)가 있으면 비밀번호 해싱에 `DefaultAuthProvider`가 필요하므로 admin 시딩이 방지된다. 경고가 로깅된다.

### Docker Compose 예시

```yaml
services:
  app:
    environment:
      ARC_REACTOR_AUTH_JWT_SECRET: "your-256-bit-secret-here-minimum-32-bytes"
      ARC_REACTOR_AUTH_ADMIN_EMAIL: "admin@example.com"
      ARC_REACTOR_AUTH_ADMIN_PASSWORD: "changeme123"
      ARC_REACTOR_AUTH_ADMIN_NAME: "Platform Admin"
```

---

## 인증 속도 제한

`AuthRateLimitFilter`는 `WebFilter` (순서 `HIGHEST_PRECEDENCE + 1`)로, 인증 엔드포인트에 대한 무차별 대입 공격을 제한한다. 필터 체인에서 `JwtAuthWebFilter`보다 먼저 실행된다.

### 동작 방식

- `POST /api/auth/login` 및 `POST /api/auth/register`에만 적용된다.
- 1분 만료 윈도우의 Caffeine 캐시를 사용하여 IP 주소별 실패 횟수를 추적한다.
- 인증 성공(2xx 응답) 시 해당 IP의 카운터를 초기화한다.
- 제한 초과 시 `Retry-After: 60` 헤더와 함께 `429 Too Many Requests`를 반환한다.

### 설정

| 속성 | 기본값 | 설명 |
|------|-------|------|
| `arc.reactor.auth.login-rate-limit-per-minute` | `10` | IP당 분당 최대 인증 실패 횟수 |
| `arc.reactor.auth.trust-forwarded-headers` | `false` | 클라이언트 IP 추출 시 `X-Forwarded-For` 헤더 신뢰 여부 |

**중요:** `trust-forwarded-headers`는 애플리케이션이 신뢰할 수 있는 리버스 프록시 뒤에서 실행될 때만 활성화해야 한다. 신뢰할 수 없는 클라이언트가 `X-Forwarded-For` 헤더를 위조하여 속도 제한을 우회할 수 있다.

---

## 토큰 폐기 저장소

사용자가 로그아웃(`POST /api/auth/logout`)하면 토큰의 `jti`(JWT ID)가 원래 만료 시각까지 폐기 저장소에 저장된다. `JwtAuthWebFilter`는 모든 요청에서 이 저장소를 확인한다.

### 구현체

| 타입 | 설정 값 | 사용 시기 | 참고 |
|------|---------|----------|------|
| `InMemoryTokenRevocationStore` | `memory` (기본) | 단일 인스턴스 배포, 개발 | ConcurrentHashMap, 최대 10,000개, 오버플로 시 자동 정리 |
| `JdbcTokenRevocationStore` | `jdbc` | 공유 PostgreSQL을 사용하는 다중 인스턴스 | Flyway 마이그레이션 `V31__create_token_revocations.sql` 필요 |
| `RedisTokenRevocationStore` | `redis` | Redis를 사용하는 다중 인스턴스 | Redis 키 TTL로 자동 만료, 키 접두사 `arc:auth:revoked` |

### 설정

```yaml
arc:
  reactor:
    auth:
      token-revocation-store: memory   # memory | jdbc | redis
```

요청한 백엔드를 사용할 수 없는 경우(예: `DataSource` 없이 `jdbc`, `StringRedisTemplate` 없이 `redis`), 경고를 로깅하고 in-memory 저장소로 폴백한다.

### DB 스키마 (Flyway V31)

`token-revocation-store: jdbc`일 때 필요:

```sql
CREATE TABLE IF NOT EXISTS auth_token_revocations (
    token_id        VARCHAR(255) PRIMARY KEY,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auth_token_revocations_expires_at
    ON auth_token_revocations (expires_at);
```

---

## 세션 격리

인증이 항상 필수이므로 사용자별 세션이 자동으로 격리된다:

### 백엔드

1. `JwtAuthWebFilter`가 JWT에서 userId 추출 → `exchange.attributes["userId"]`
2. `ChatController.resolveUserId()`가 exchange에서 userId 우선 사용
3. `ConversationManager`가 userId와 함께 `MemoryStore.addMessage(sessionId, role, content, userId)` 호출
4. `SessionController`가 `listSessionsByUserId(userId)`로 해당 사용자 세션만 반환
5. `getSession()`/`deleteSession()`에서 소유권 확인 -- 불일치 시 403

### 프론트엔드

1. 앱 시작 시 JWT 없이 `GET /api/models` 호출 -- `401` 반환이 정상 런타임 계약
2. 로그인 성공 -- JWT 토큰을 `localStorage`에 저장
3. `fetchWithAuth()` -- 모든 API 요청에 `Authorization: Bearer <token>` 자동 주입
4. `localStorage` 키를 userId별 네임스페이스로 분리: `arc-reactor-sessions:{userId}`
5. `ChatProvider key={user?.id}` -- 사용자 변경 시 전체 리마운트 (세션 혼재 방지)
6. 401 응답 시 자동 로그아웃 (토큰 만료 처리)

---

## DB 스키마

### users 테이블 (Flyway V3)

```sql
CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(36)   PRIMARY KEY,
    email         VARCHAR(255)  NOT NULL UNIQUE,
    name          VARCHAR(100)  NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users (email);
```

### conversation_messages userId 추가 (Flyway V4)

```sql
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(36) NOT NULL DEFAULT 'anonymous';

CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_id
    ON conversation_messages (user_id);
CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_session
    ON conversation_messages (user_id, session_id);
```

기존 데이터는 `'anonymous'`로 자동 마이그레이션된다.

---

## 보안 참고사항

| 항목 | 구현 |
|------|------|
| 비밀번호 저장 | BCrypt (spring-security-crypto) |
| 토큰 서명 | HS256 (HMAC-SHA256) |
| 토큰 전송 | `Authorization: Bearer` 헤더 |
| 토큰 폐기 | `POST /api/auth/logout`이 토큰 `jti`를 만료 시점까지 저장 (memory, JDBC, 또는 Redis) |
| 로그인 무차별 대입 방어 | `AuthRateLimitFilter` -- 인증 엔드포인트에 대한 IP별 속도 제한 (기본: 10/분) |
| 토큰 저장 (프론트) | localStorage |
| CORS | Vite 프록시 또는 nginx reverse proxy 사용 |
| 비밀번호 검증 | 최소 8자 (`@Size(min=8)`) |
| 이메일 검증 | `@Email` 형식 검증 |
| 중복 가입 방지 | DB unique constraint + API 레벨 체크 |
| Admin 인가 | `AdminAuthorizationSupport.isAdmin(exchange)` -- null 역할에 대해 fail-close |

### jwt-secret 생성 권장

```bash
# 256-bit 시크릿 생성
openssl rand -base64 32
```

`.env` 파일에 저장하고 소스에 커밋하지 말 것.

---

## 참고 코드

- [`auth/AuthModels.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthModels.kt) -- User, UserRole, AdminScope, AuthProperties
- [`auth/AuthProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthProvider.kt) -- 인증 인터페이스
- [`auth/UserStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/UserStore.kt) -- 사용자 저장소 인터페이스 + InMemory 구현
- [`auth/JdbcUserStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JdbcUserStore.kt) -- PostgreSQL 구현
- [`auth/JwtTokenProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JwtTokenProvider.kt) -- JWT 토큰 생성/검증
- [`auth/JwtAuthWebFilter.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JwtAuthWebFilter.kt) -- WebFilter (인증)
- [`auth/AuthRateLimitFilter.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthRateLimitFilter.kt) -- WebFilter (무차별 대입 방어)
- [`auth/AdminInitializer.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AdminInitializer.kt) -- 기동 시 admin 계정 부트스트랩
- [`auth/AdminAuthorizationSupport.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AdminAuthorizationSupport.kt) -- Admin 인가 헬퍼
- [`auth/TokenRevocationStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/TokenRevocationStore.kt) -- 폐기 저장소 인터페이스 + Memory/JDBC/Redis 구현
- [`auth/DefaultAuthProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/DefaultAuthProvider.kt) -- BCrypt 기본 구현
- [`controller/AuthController.kt`](../../../arc-web/src/main/kotlin/com/arc/reactor/controller/AuthController.kt) -- REST 엔드포인트
- [`V3__create_users.sql`](../../../arc-core/src/main/resources/db/migration/V3__create_users.sql) -- 사용자 테이블
- [`V4__add_user_id_to_conversation_messages.sql`](../../../arc-core/src/main/resources/db/migration/V4__add_user_id_to_conversation_messages.sql) -- userId 컬럼 추가
- [`V31__create_token_revocations.sql`](../../../arc-core/src/main/resources/db/migration/V31__create_token_revocations.sql) -- 토큰 폐기 테이블
