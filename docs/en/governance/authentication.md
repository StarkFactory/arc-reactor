# JWT Authentication System

## TL;DR

**JWT authentication is runtime-required.** Enterprises can swap in custom authentication (LDAP/SSO, etc.) by implementing the `AuthProvider` interface.

---

## Runtime Requirement

Authentication is always enabled in runtime environments:

- `arc.reactor.auth.jwt-secret` configured with a 32+ byte secret
- Missing or invalid auth configuration fails startup

---

## Architecture

```
Request
  |
  v
+------------------------------------------------------+
|  AuthRateLimitFilter (HIGHEST_PRECEDENCE + 1)        |
|                                                       |
|  POST /api/auth/login, /api/auth/register             |
|  -> Under limit: pass through                         |
|  -> Over limit: 429 Too Many Requests                 |
+------------------------------------------------------+
  |
  v
+------------------------------------------------------+
|  JwtAuthWebFilter (HIGHEST_PRECEDENCE + 2)           |
|                                                       |
|  /api/auth/login (+ /api/auth/register when enabled)  |
|  -> pass (public)                                     |
|  Others -> Verify Authorization: Bearer <token>       |
|       -> Valid: exchange.attributes["userId"] = userId|
|       -> Invalid: 401 Unauthorized response           |
+------------------------------------------------------+
  |
  v
+------------------------------------------------------+
|  Controllers (ChatController, SessionController, etc)|
|                                                       |
|  Identify authenticated user via                      |
|  exchange.attributes["userId"]                        |
|  -> Guard rate limit key, session isolation,          |
|     ownership verification                            |
+------------------------------------------------------+
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| No Spring Security | Lightweight, minimal framework dependency |
| WebFilter (not Interceptor) | WebFlux reactive compatible |
| `ServerWebExchange.attributes` | Reactive-safe approach, not ThreadLocal |
| JJWT + spring-security-crypto only | Uses only the libraries, not the full Spring Security framework |
| Auth deps enabled by default | Reduces startup confusion and runtime bean mismatch |

---

## Configuration

### application.yml

```yaml
arc:
  reactor:
    auth:
      jwt-secret: ${JWT_SECRET}              # HS256 signing secret (32+ bytes recommended)
      jwt-expiration-ms: 86400000            # Token validity period (default: 24 hours)
      self-registration-enabled: false       # Default: closed registration (recommended)
      login-rate-limit-per-minute: 10        # Max failed auth attempts per IP per minute
      trust-forwarded-headers: false         # Trust X-Forwarded-For for IP extraction (proxy setups)
      token-revocation-store: memory         # Revoked token backend: memory | jdbc | redis
      public-paths:                          # Paths accessible without authentication
        - /api/auth/login
```

### Build (build.gradle.kts)

```bash
# Authentication is runtime-required and included by default
./gradlew :arc-app:bootRun

# Build bootJar with DB support
./gradlew :arc-app:bootJar -Pdb=true
```

### Docker

```dockerfile
# Build includes auth by default; ARG controls DB packaging
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

## API Endpoints

`AuthController` is always registered.

Issued JWT tokens include a `tenantId` claim. Default is `default` (configurable with
`arc.reactor.auth.default-tenant-id`).

### POST /api/auth/register -- Sign Up

Enabled only when `arc.reactor.auth.self-registration-enabled=true` (default: `false`).

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123",
    "name": "User Name"
  }'
```

**Response (201 Created):**
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

**Error (409 Conflict):**
```json
{
  "token": "",
  "user": null,
  "error": "Email already registered"
}
```

**Error (403 Forbidden, default):**
```json
{
  "token": "",
  "user": null,
  "error": "Self-registration is disabled. Contact an administrator."
}
```

**Validation:**
- `email`: Valid email format (`@Email`)
- `password`: Minimum 8 characters (`@Size(min=8)`)
- `name`: Cannot be blank (`@NotBlank`)

### POST /api/auth/login -- Log In

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "password123"
  }'
```

**Response (200 OK):**
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

**Error (401 Unauthorized):**
```json
{
  "token": "",
  "user": null,
  "error": "Invalid email or password"
}
```

### GET /api/auth/me -- Get Current User

```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "name": "User Name"
}
```

### POST /api/auth/change-password -- Change Password

```bash
curl -X POST http://localhost:8080/api/auth/change-password \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "currentPassword": "password123",
    "newPassword": "newpassword456"
  }'
```

### POST /api/auth/logout -- Revoke Current Token

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

---

## Component Structure

### Interfaces

| Interface | Role | Default Implementation |
|-----------|------|----------------------|
| `AuthProvider` | Authentication logic (authenticate, getUserById) | `DefaultAuthProvider` (BCrypt) |
| `UserStore` | User CRUD (findByEmail, save, update) | `InMemoryUserStore` / `JdbcUserStore` |
| `TokenRevocationStore` | Tracks revoked JWT `jti` values until original expiry | `InMemoryTokenRevocationStore` / `JdbcTokenRevocationStore` / `RedisTokenRevocationStore` |

### Classes

| Class | Role |
|-------|------|
| `AuthModels.kt` | `User`, `UserRole`, `AdminScope`, `AuthProperties`, `TokenRevocationStoreType` |
| `JwtTokenProvider` | Token creation (`createToken`) / validation (`validateToken`) via JJWT |
| `JwtAuthWebFilter` | WebFilter -- validates Bearer token on every request |
| `AuthRateLimitFilter` | WebFilter -- rate-limits POST requests to `/api/auth/login` and `/api/auth/register` |
| `AdminInitializer` | Bootstraps an initial ADMIN account on startup from environment variables |
| `AdminAuthorizationSupport` | Shared admin authorization helpers (`isAdmin`, `isAnyAdmin`) |
| `DefaultAuthProvider` | Default BCrypt password verification implementation |
| `InMemoryUserStore` | ConcurrentHashMap-based (for development) |
| `JdbcUserStore` | PostgreSQL `users` table (for production) |
| `AuthController` | REST endpoints (register, login, me, change-password, logout) |

### Auto-Configuration Beans

All auth beans are always registered when auth dependencies are on the classpath.

```
arc.reactor.auth.jwt-secret set
  |
  +-- AuthProperties
  +-- JwtTokenProvider
  +-- AuthRateLimitFilter (WebFilter, HIGHEST_PRECEDENCE + 1)
  +-- JwtAuthWebFilter (WebFilter, HIGHEST_PRECEDENCE + 2)
  +-- UserStore
  |     +-- If DataSource exists -> JdbcUserStore (@Primary)
  |     +-- If DataSource absent -> InMemoryUserStore
  +-- AuthProvider
  |     +-- @ConditionalOnMissingBean -> DefaultAuthProvider
  +-- TokenRevocationStore
  |     +-- memory (default) -> InMemoryTokenRevocationStore
  |     +-- jdbc -> JdbcTokenRevocationStore (fallback: in-memory)
  |     +-- redis -> RedisTokenRevocationStore (fallback: in-memory)
  +-- AdminInitializer (creates admin account on startup)
  +-- AuthController (@RestController)
```

---

## JWT Token Structure

```
Header:  { "alg": "HS256" }
Payload: {
  "jti": "d4f0...",                // Token ID (revocation key)
  "sub": "550e8400-...",          // userId
  "email": "user@example.com",    // User email
  "role": "ADMIN",                // User role
  "tenantId": "default",          // Tenant isolation key
  "iat": 1707350400,              // Issued at
  "exp": 1707436800               // Expiration (24 hours later)
}
Signature: HMACSHA256(header + payload, jwt-secret)
```

---

## Custom Authentication Implementation

### Replacing AuthProvider (LDAP, SSO, etc.)

```kotlin
@Component
class LdapAuthProvider(private val ldapTemplate: LdapTemplate) : AuthProvider {

    override fun authenticate(email: String, password: String): User? {
        // LDAP authentication logic
        val ldapUser = ldapTemplate.authenticate(email, password)
            ?: return null
        return User(
            id = ldapUser.uid,
            email = ldapUser.email,
            name = ldapUser.displayName,
            passwordHash = "" // Empty since LDAP manages passwords
        )
    }

    override fun getUserById(userId: String): User? {
        // Look up user from LDAP
        return ldapTemplate.findByUid(userId)?.toUser()
    }
}
```

Registering with `@Component` automatically disables `DefaultAuthProvider` via `@ConditionalOnMissingBean`.

### Replacing UserStore (Redis, etc.)

```kotlin
@Component
class RedisUserStore(private val redisTemplate: RedisTemplate<String, User>) : UserStore {
    override fun findByEmail(email: String): User? { /* Redis lookup */ }
    override fun findById(id: String): User? { /* Redis lookup */ }
    override fun save(user: User): User { /* Redis save */ }
    override fun existsByEmail(email: String): Boolean { /* Redis existence check */ }
}
```

---

## User Roles and Admin Scopes

### UserRole

Each user has a `role` field stored in the database and embedded in their JWT token. The role controls access to admin endpoints and dashboards.

| Role | Description | `isAnyAdmin()` | `isDeveloperAdmin()` |
|------|-------------|:---:|:---:|
| `USER` | Standard access (chat, persona selection) | no | no |
| `ADMIN` | Full admin access (legacy role, grants all admin permissions) | yes | yes |
| `ADMIN_MANAGER` | Manager-facing dashboards only | yes | no |
| `ADMIN_DEVELOPER` | Developer/admin control surfaces | yes | yes |

### AdminScope

`AdminScope` is a coarse-grained enum derived from `UserRole` for frontend workspace decisions. Each admin role maps to a scope:

| AdminScope | Mapped from UserRole | Purpose |
|------------|---------------------|---------|
| `FULL` | `ADMIN` | Unrestricted admin access |
| `MANAGER` | `ADMIN_MANAGER` | Manager dashboards (usage analytics, billing) |
| `DEVELOPER` | `ADMIN_DEVELOPER` | Developer control surfaces (tool config, prompts, MCP) |

`USER` role has no `AdminScope` (returns `null`).

### AdminAuthorizationSupport

Admin authorization in controllers must always use `AdminAuthorizationSupport`:

- `AdminAuthorizationSupport.isAdmin(exchange)` -- checks developer-level admin access (`ADMIN` or `ADMIN_DEVELOPER`)
- `AdminAuthorizationSupport.isAnyAdmin(exchange)` -- checks any admin role (`ADMIN`, `ADMIN_MANAGER`, or `ADMIN_DEVELOPER`)

Null role is treated as non-admin (fail-close). Never duplicate this logic inline.

---

## Admin Initialization

`AdminInitializer` creates an initial ADMIN account on server startup from environment variables. It runs once on the `ApplicationReadyEvent`.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ARC_REACTOR_AUTH_ADMIN_EMAIL` | Yes | Admin account email |
| `ARC_REACTOR_AUTH_ADMIN_PASSWORD` | Yes | Admin account password (minimum 8 characters) |
| `ARC_REACTOR_AUTH_ADMIN_NAME` | No | Display name (default: `"Admin"`) |

### Behavior

1. If the environment variables are not set, initialization is silently skipped.
2. If the password is shorter than 8 characters, initialization is skipped with a warning.
3. If a user with the given email already exists, initialization is skipped (idempotent).
4. A custom `AuthProvider` (e.g., LDAP) prevents admin seeding because password hashing requires `DefaultAuthProvider`. A warning is logged.

### Docker Compose Example

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

## Auth Rate Limiting

`AuthRateLimitFilter` is a `WebFilter` (order `HIGHEST_PRECEDENCE + 1`) that limits brute-force attempts on authentication endpoints. It runs before `JwtAuthWebFilter` in the filter chain.

### How It Works

- Applies only to `POST /api/auth/login` and `POST /api/auth/register`.
- Tracks failed attempts per IP address using a Caffeine cache with a 1-minute expiry window.
- Successful authentication (2xx response) resets the counter for that IP.
- When the limit is exceeded, returns `429 Too Many Requests` with a `Retry-After: 60` header.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `arc.reactor.auth.login-rate-limit-per-minute` | `10` | Maximum failed authentication attempts per IP per minute |
| `arc.reactor.auth.trust-forwarded-headers` | `false` | Trust `X-Forwarded-For` header for client IP extraction |

**Important:** Only enable `trust-forwarded-headers` when the application runs behind a trusted reverse proxy. An untrusted client can forge the `X-Forwarded-For` header to bypass rate limiting.

---

## Token Revocation Store

When a user logs out (`POST /api/auth/logout`), the token's `jti` (JWT ID) is stored in the revocation store until the token's original expiration time. `JwtAuthWebFilter` checks this store on every request.

### Implementations

| Type | Config value | When to use | Notes |
|------|-------------|-------------|-------|
| `InMemoryTokenRevocationStore` | `memory` (default) | Single-instance deployments, development | ConcurrentHashMap, max 10,000 entries, auto-purge on overflow |
| `JdbcTokenRevocationStore` | `jdbc` | Multi-instance with shared PostgreSQL | Requires Flyway migration `V31__create_token_revocations.sql` |
| `RedisTokenRevocationStore` | `redis` | Multi-instance with Redis | Uses Redis key TTL for automatic expiry, key prefix `arc:auth:revoked` |

### Configuration

```yaml
arc:
  reactor:
    auth:
      token-revocation-store: memory   # memory | jdbc | redis
```

If the requested backend is unavailable (e.g., `jdbc` without a `DataSource`, or `redis` without a `StringRedisTemplate`), the system logs a warning and falls back to the in-memory store.

### DB Schema (Flyway V31)

Required when `token-revocation-store: jdbc`:

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

## Session Isolation

Because authentication is always required, per-user session isolation is automatic:

### Backend

1. `JwtAuthWebFilter` extracts userId from JWT and sets `exchange.attributes["userId"]`
2. `ChatController.resolveUserId()` prioritizes userId from exchange
3. `ConversationManager` calls `MemoryStore.addMessage(sessionId, role, content, userId)` with the userId
4. `SessionController` returns only the user's sessions via `listSessionsByUserId(userId)`
5. `getSession()`/`deleteSession()` verify ownership -- returns 403 on mismatch

### Frontend

1. On app startup, `GET /api/models` without JWT returns `401` (expected runtime contract)
2. On successful login, JWT token is stored in `localStorage`
3. `fetchWithAuth()` -- automatically injects `Authorization: Bearer <token>` on all API requests
4. `localStorage` keys are namespaced per userId: `arc-reactor-sessions:{userId}`
5. `ChatProvider key={user?.id}` -- full remount on user change (prevents session mixing)
6. Automatic logout on 401 response (handles token expiration)

---

## DB Schema

### users Table (Flyway V3)

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

### Adding userId to conversation_messages (Flyway V4)

```sql
ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(36) NOT NULL DEFAULT 'anonymous';

CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_id
    ON conversation_messages (user_id);
CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_session
    ON conversation_messages (user_id, session_id);
```

Existing data is automatically migrated with `'anonymous'`.

---

## Security Notes

| Item | Implementation |
|------|---------------|
| Password storage | BCrypt (spring-security-crypto) |
| Token signing | HS256 (HMAC-SHA256) |
| Token transmission | `Authorization: Bearer` header |
| Token revocation | `POST /api/auth/logout` stores token `jti` until expiration (memory, JDBC, or Redis) |
| Login brute-force protection | `AuthRateLimitFilter` -- per-IP rate limit on auth endpoints (default: 10/min) |
| Token storage (frontend) | localStorage |
| CORS | Vite proxy or nginx reverse proxy |
| Password validation | Minimum 8 characters (`@Size(min=8)`) |
| Email validation | `@Email` format validation |
| Duplicate registration prevention | DB unique constraint + API-level check |
| Admin authorization | `AdminAuthorizationSupport.isAdmin(exchange)` -- fail-close on null role |

### Recommended jwt-secret Generation

```bash
# Generate a 256-bit secret
openssl rand -base64 32
```

Store in a `.env` file and never commit to source control.

---

## Reference Code

- [`auth/AuthModels.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthModels.kt) -- User, UserRole, AdminScope, AuthProperties
- [`auth/AuthProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthProvider.kt) -- Authentication interface
- [`auth/UserStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/UserStore.kt) -- User store interface + InMemory implementation
- [`auth/JdbcUserStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JdbcUserStore.kt) -- PostgreSQL implementation
- [`auth/JwtTokenProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JwtTokenProvider.kt) -- JWT token creation/validation
- [`auth/JwtAuthWebFilter.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JwtAuthWebFilter.kt) -- WebFilter (authentication)
- [`auth/AuthRateLimitFilter.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthRateLimitFilter.kt) -- WebFilter (brute-force protection)
- [`auth/AdminInitializer.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AdminInitializer.kt) -- Startup admin account bootstrap
- [`auth/AdminAuthorizationSupport.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AdminAuthorizationSupport.kt) -- Admin authorization helpers
- [`auth/TokenRevocationStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/TokenRevocationStore.kt) -- Revocation store interface + Memory/JDBC/Redis implementations
- [`auth/DefaultAuthProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/DefaultAuthProvider.kt) -- BCrypt default implementation
- [`controller/AuthController.kt`](../../../arc-web/src/main/kotlin/com/arc/reactor/controller/AuthController.kt) -- REST endpoints
- [`V3__create_users.sql`](../../../arc-core/src/main/resources/db/migration/V3__create_users.sql) -- Users table
- [`V4__add_user_id_to_conversation_messages.sql`](../../../arc-core/src/main/resources/db/migration/V4__add_user_id_to_conversation_messages.sql) -- userId column addition
- [`V31__create_token_revocations.sql`](../../../arc-core/src/main/resources/db/migration/V31__create_token_revocations.sql) -- Token revocations table
