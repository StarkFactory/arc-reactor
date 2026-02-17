# JWT 인증 시스템

## 한 줄 요약

**`arc.reactor.auth.enabled=true` 하나로 켜지는 opt-in JWT 인증.** 기업은 `AuthProvider` 인터페이스만 구현하면 LDAP/SSO 등 커스텀 인증으로 교체 가능.

---

## 왜 opt-in인가?

Arc Reactor는 로컬 개발과 빠른 프로토타이핑이 중요하다. 인증은 필요할 때만 켜야 한다:

- **로컬 개발** → 인증 없이 바로 채팅 (`arc.reactor.auth.enabled=false`, 기본값)
- **팀/사내 배포** → 인증 켜서 사용자별 세션 격리 (`arc.reactor.auth.enabled=true`)
- **기업 fork** → `AuthProvider` 교체로 사내 인증 시스템 연동

인증 비활성 시 auth 관련 빈은 하나도 등록되지 않는다. 기존 코드에 영향 zero.

---

## 아키텍처

```
요청
  │
  ▼
┌──────────────────────────────────────────────────────┐
│  JwtAuthWebFilter (WebFilter, HIGHEST_PRECEDENCE)    │
│                                                       │
│  /api/auth/login, /api/auth/register → 통과 (public) │
│  그 외 → Authorization: Bearer <token> 검증           │
│       → 유효: exchange.attributes["userId"] = userId  │
│       → 무효: 401 Unauthorized 응답                   │
└──────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────┐
│  Controllers (ChatController, SessionController 등)  │
│                                                       │
│  exchange.attributes["userId"]로 인증된 사용자 식별   │
│  → Guard rate limit 키, 세션 격리, 소유권 확인        │
└──────────────────────────────────────────────────────┘
```

### 핵심 설계 결정

| 결정 | 이유 |
|------|------|
| Spring Security 없음 | 경량, 프레임워크 의존성 최소화 |
| WebFilter (not Interceptor) | WebFlux reactive 호환 |
| `ServerWebExchange.attributes` | ThreadLocal이 아닌 reactive-safe 방식 |
| JJWT + spring-security-crypto만 | Spring Security 전체가 아닌 라이브러리만 사용 |
| `-Pauth=true` 빌드 플래그 | 선택적 의존성 — 필요 없으면 바이너리에 미포함 |

---

## 설정

### application.yml

```yaml
arc:
  reactor:
    auth:
      enabled: true                    # JWT 인증 활성화
      jwt-secret: ${JWT_SECRET}        # HS256 서명 시크릿 (32바이트 이상 권장)
      jwt-expiration-ms: 86400000      # 토큰 유효기간 (기본: 24시간)
      public-paths:                    # 인증 없이 접근 가능한 경로
        - /api/auth/login
        - /api/auth/register
```

### 빌드 (build.gradle.kts)

```bash
# 개발: auth 의존성 없이 빌드 (기본)
./gradlew :arc-app:bootRun

# 프로덕션: auth 의존성 포함 빌드
./gradlew bootJar -Pauth=true

# DB + Auth 모두 포함
./gradlew bootJar -Pdb=true -Pauth=true
```

`-Pauth=true` 플래그를 사용하면 JJWT와 spring-security-crypto가 `compileOnly`에서 `implementation`으로 전환된다.

### Docker

```dockerfile
# Dockerfile에서 ARG로 제어
ARG ENABLE_AUTH=false
RUN ./gradlew bootJar -Pdb=true -Pauth=${ENABLE_AUTH}
```

```yaml
# docker-compose.yml
services:
  app:
    build:
      args:
        ENABLE_AUTH: "true"
    environment:
      ARC_REACTOR_AUTH_ENABLED: "true"
      ARC_REACTOR_AUTH_JWT_SECRET: "your-256-bit-secret-here-minimum-32-bytes"
```

---

## API 엔드포인트

인증 활성 시 `AuthController`가 자동 등록된다.

### POST /api/auth/register — 회원가입

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

**검증:**
- `email`: 유효한 이메일 형식 (`@Email`)
- `password`: 8자 이상 (`@Size(min=8)`)
- `name`: 빈 값 불가 (`@NotBlank`)

### POST /api/auth/login — 로그인

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

### GET /api/auth/me — 현재 사용자 조회

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

### POST /api/auth/change-password — 비밀번호 변경

```bash
curl -X POST http://localhost:8080/api/auth/change-password \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "currentPassword": "password123",
    "newPassword": "newpassword456"
  }'
```

---

## 컴포넌트 구조

### 인터페이스

| 인터페이스 | 역할 | 기본 구현 |
|-----------|------|---------|
| `AuthProvider` | 인증 로직 (authenticate, getUserById) | `DefaultAuthProvider` (BCrypt) |
| `UserStore` | 사용자 CRUD (findByEmail, save, update) | `InMemoryUserStore` / `JdbcUserStore` |

### 클래스

| 클래스 | 역할 |
|-------|------|
| `AuthModels.kt` | `User` 데이터 클래스, `AuthProperties` 설정 |
| `JwtTokenProvider` | JJWT로 토큰 생성 (`createToken`) / 검증 (`validateToken`) |
| `JwtAuthWebFilter` | WebFilter — 모든 요청에서 Bearer 토큰 검증 |
| `DefaultAuthProvider` | BCrypt 비밀번호 검증 기본 구현 |
| `InMemoryUserStore` | ConcurrentHashMap 기반 (개발용) |
| `JdbcUserStore` | PostgreSQL `users` 테이블 (프로덕션용) |
| `AuthController` | REST 엔드포인트 (register, login, me, change-password) |

### 자동 구성 빈

모든 auth 빈은 `@ConditionalOnProperty(prefix = "arc.reactor.auth", name = ["enabled"], havingValue = "true")` 조건 하에 등록된다.

```
arc.reactor.auth.enabled=true
  │
  ├── AuthProperties
  ├── JwtTokenProvider
  ├── JwtAuthWebFilter (WebFilter)
  ├── UserStore
  │     └── DataSource 있으면 → JdbcUserStore (@Primary)
  │     └── DataSource 없으면 → InMemoryUserStore
  ├── AuthProvider
  │     └── @ConditionalOnMissingBean → DefaultAuthProvider
  └── AuthController (@RestController)
```

---

## JWT 토큰 구조

```
Header:  { "alg": "HS256" }
Payload: {
  "sub": "550e8400-...",          // userId
  "email": "user@example.com",    // 사용자 이메일
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

## 세션 격리

인증 활성 시 자동으로 사용자별 세션이 격리된다:

### 백엔드

1. `JwtAuthWebFilter`가 JWT에서 userId 추출 → `exchange.attributes["userId"]`
2. `ChatController.resolveUserId()`가 exchange에서 userId 우선 사용
3. `ConversationManager`가 userId와 함께 `MemoryStore.addMessage(sessionId, role, content, userId)` 호출
4. `SessionController`가 `listSessionsByUserId(userId)`로 해당 사용자 세션만 반환
5. `getSession()`/`deleteSession()`에서 소유권 확인 → 불일치 시 403

### 프론트엔드

1. 앱 시작 시 `GET /api/models` 프로브 → 401이면 auth 필요, 200이면 auth 비활성
2. 로그인 성공 → JWT 토큰을 `localStorage`에 저장
3. `fetchWithAuth()` — 모든 API 요청에 `Authorization: Bearer <token>` 자동 주입
4. `localStorage` 키를 userId별 네임스페이스로 분리: `arc-reactor-sessions:{userId}`
5. `ChatProvider key={user?.id}` — 사용자 변경 시 전체 리마운트 (세션 혼재 방지)
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
| 토큰 저장 (프론트) | localStorage |
| CORS | Vite 프록시 또는 nginx reverse proxy 사용 |
| 비밀번호 검증 | 최소 8자 (`@Size(min=8)`) |
| 이메일 검증 | `@Email` 형식 검증 |
| 중복 가입 방지 | DB unique constraint + API 레벨 체크 |

### jwt-secret 생성 권장

```bash
# 256-bit 시크릿 생성
openssl rand -base64 32
```

`.env` 파일에 저장하고 소스에 커밋하지 말 것.

---

## 참고 코드

- [`auth/AuthProvider.kt`](../../src/main/kotlin/com/arc/reactor/auth/AuthProvider.kt) — 인증 인터페이스
- [`auth/UserStore.kt`](../../src/main/kotlin/com/arc/reactor/auth/UserStore.kt) — 사용자 저장소 인터페이스 + InMemory 구현
- [`auth/JdbcUserStore.kt`](../../src/main/kotlin/com/arc/reactor/auth/JdbcUserStore.kt) — PostgreSQL 구현
- [`auth/JwtTokenProvider.kt`](../../src/main/kotlin/com/arc/reactor/auth/JwtTokenProvider.kt) — JWT 토큰 생성/검증
- [`auth/JwtAuthWebFilter.kt`](../../src/main/kotlin/com/arc/reactor/auth/JwtAuthWebFilter.kt) — WebFilter
- [`auth/DefaultAuthProvider.kt`](../../src/main/kotlin/com/arc/reactor/auth/DefaultAuthProvider.kt) — BCrypt 기본 구현
- [`controller/AuthController.kt`](../../src/main/kotlin/com/arc/reactor/controller/AuthController.kt) — REST 엔드포인트
- [`V3__create_users.sql`](../../src/main/resources/db/migration/V3__create_users.sql) — 사용자 테이블
- [`V4__add_user_id_to_conversation_messages.sql`](../../src/main/resources/db/migration/V4__add_user_id_to_conversation_messages.sql) — userId 컬럼 추가
