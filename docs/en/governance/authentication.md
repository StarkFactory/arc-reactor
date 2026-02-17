# JWT Authentication System

## TL;DR

**Opt-in JWT authentication enabled with a single `arc.reactor.auth.enabled=true`.** Enterprises can swap in custom authentication (LDAP/SSO, etc.) by simply implementing the `AuthProvider` interface.

---

## Why Opt-in?

Local development and rapid prototyping are important for Arc Reactor. Authentication should only be enabled when needed:

- **Local development** -- Chat without authentication (`arc.reactor.auth.enabled=false`, default)
- **Team/internal deployment** -- Enable authentication for per-user session isolation (`arc.reactor.auth.enabled=true`)
- **Enterprise fork** -- Swap `AuthProvider` to integrate with internal authentication systems

When authentication is disabled, no auth-related beans are registered at all. Zero impact on existing code.

---

## Architecture

```
Request
  |
  v
+------------------------------------------------------+
|  JwtAuthWebFilter (WebFilter, HIGHEST_PRECEDENCE)    |
|                                                       |
|  /api/auth/login, /api/auth/register -> pass (public)|
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
| `-Pauth=true` build flag | Optional dependency -- excluded from binary when not needed |

---

## Configuration

### application.yml

```yaml
arc:
  reactor:
    auth:
      enabled: true                    # Enable JWT authentication
      jwt-secret: ${JWT_SECRET}        # HS256 signing secret (32+ bytes recommended)
      jwt-expiration-ms: 86400000      # Token validity period (default: 24 hours)
      public-paths:                    # Paths accessible without authentication
        - /api/auth/login
        - /api/auth/register
```

### Build (build.gradle.kts)

```bash
# Development: Build without auth dependencies (default)
./gradlew :arc-app:bootRun

# Production: Build with auth dependencies included
./gradlew bootJar -Pauth=true

# DB + Auth both included
./gradlew bootJar -Pdb=true -Pauth=true
```

When the `-Pauth=true` flag is used, JJWT and spring-security-crypto switch from `compileOnly` to `implementation`.

### Docker

```dockerfile
# Control via ARG in Dockerfile
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

## API Endpoints

When authentication is enabled, `AuthController` is automatically registered.

### POST /api/auth/register -- Sign Up

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

---

## Component Structure

### Interfaces

| Interface | Role | Default Implementation |
|-----------|------|----------------------|
| `AuthProvider` | Authentication logic (authenticate, getUserById) | `DefaultAuthProvider` (BCrypt) |
| `UserStore` | User CRUD (findByEmail, save, update) | `InMemoryUserStore` / `JdbcUserStore` |

### Classes

| Class | Role |
|-------|------|
| `AuthModels.kt` | `User` data class, `AuthProperties` configuration |
| `JwtTokenProvider` | Token creation (`createToken`) / validation (`validateToken`) via JJWT |
| `JwtAuthWebFilter` | WebFilter -- validates Bearer token on every request |
| `DefaultAuthProvider` | Default BCrypt password verification implementation |
| `InMemoryUserStore` | ConcurrentHashMap-based (for development) |
| `JdbcUserStore` | PostgreSQL `users` table (for production) |
| `AuthController` | REST endpoints (register, login, me, change-password) |

### Auto-Configuration Beans

All auth beans are registered under the condition `@ConditionalOnProperty(prefix = "arc.reactor.auth", name = ["enabled"], havingValue = "true")`.

```
arc.reactor.auth.enabled=true
  |
  +-- AuthProperties
  +-- JwtTokenProvider
  +-- JwtAuthWebFilter (WebFilter)
  +-- UserStore
  |     +-- If DataSource exists -> JdbcUserStore (@Primary)
  |     +-- If DataSource absent -> InMemoryUserStore
  +-- AuthProvider
  |     +-- @ConditionalOnMissingBean -> DefaultAuthProvider
  +-- AuthController (@RestController)
```

---

## JWT Token Structure

```
Header:  { "alg": "HS256" }
Payload: {
  "sub": "550e8400-...",          // userId
  "email": "user@example.com",    // User email
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

## Session Isolation

When authentication is enabled, per-user session isolation is automatic:

### Backend

1. `JwtAuthWebFilter` extracts userId from JWT and sets `exchange.attributes["userId"]`
2. `ChatController.resolveUserId()` prioritizes userId from exchange
3. `ConversationManager` calls `MemoryStore.addMessage(sessionId, role, content, userId)` with the userId
4. `SessionController` returns only the user's sessions via `listSessionsByUserId(userId)`
5. `getSession()`/`deleteSession()` verify ownership -- returns 403 on mismatch

### Frontend

1. On app startup, `GET /api/models` probe -- 401 means auth required, 200 means auth disabled
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
| Token storage (frontend) | localStorage |
| CORS | Vite proxy or nginx reverse proxy |
| Password validation | Minimum 8 characters (`@Size(min=8)`) |
| Email validation | `@Email` format validation |
| Duplicate registration prevention | DB unique constraint + API-level check |

### Recommended jwt-secret Generation

```bash
# Generate a 256-bit secret
openssl rand -base64 32
```

Store in a `.env` file and never commit to source control.

---

## Reference Code

- [`auth/AuthProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/AuthProvider.kt) -- Authentication interface
- [`auth/UserStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/UserStore.kt) -- User store interface + InMemory implementation
- [`auth/JdbcUserStore.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JdbcUserStore.kt) -- PostgreSQL implementation
- [`auth/JwtTokenProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JwtTokenProvider.kt) -- JWT token creation/validation
- [`auth/JwtAuthWebFilter.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/JwtAuthWebFilter.kt) -- WebFilter
- [`auth/DefaultAuthProvider.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/auth/DefaultAuthProvider.kt) -- BCrypt default implementation
- [`controller/AuthController.kt`](../../../arc-web/src/main/kotlin/com/arc/reactor/controller/AuthController.kt) -- REST endpoints
- [`V3__create_users.sql`](../../../arc-core/src/main/resources/db/migration/V3__create_users.sql) -- Users table
- [`V4__add_user_id_to_conversation_messages.sql`](../../../arc-core/src/main/resources/db/migration/V4__add_user_id_to_conversation_messages.sql) -- userId column addition
