# 인증 아키텍처: aslan-iam 중앙 인증 + 토큰 교환

## 개요

Aslan 팀의 모든 서비스는 **aslan-iam**을 통해 인증(Authentication)하고, 각 서비스는 **인가(Authorization)만** 자체 처리한다.

```
인증(Authentication) = "이 사람이 누구인가?" → aslan-iam 단독 담당
인가(Authorization)  = "이 사람이 뭘 할 수 있는가?" → 각 서비스가 담당
```

## 인증 흐름

### 프로덕션 (aslan-iam 연동)

```
사용자
  │
  ├─ 1. 로그인 요청 (email + password)
  ▼
aslan-iam
  │
  ├─ 2. 인증 성공 → IAM 토큰 발급 (RS256, JWT)
  │     { sub: "user-uuid", roles: [...], iss: "aslan-iam" }
  ▼
프론트엔드 (arc-reactor-admin / aslan-verse-web / 기타)
  │
  ├─ 3. IAM 토큰으로 서비스 토큰 교환
  │     POST /api/auth/exchange { token: "iam-jwt" }
  ▼
각 서비스 (arc-reactor 등)
  │
  ├─ 4. IAM 토큰 검증 (RS256 공개키)
  ├─ 5. 사용자 매핑 (IAM UUID → 로컬 User)
  ├─ 6. 로컬 토큰 발급 (HS256, 서비스별 권한 포함)
  │     { sub: "user-id", role: "ADMIN", tenantId: "default" }
  ▼
  └─ 7. 이후 모든 API 호출은 로컬 토큰 사용
```

### 개발 환경 (IAM 없이)

```
사용자
  │
  ├─ 1. 직접 로그인 (email + password)
  │     POST /api/auth/login { email, password }
  ▼
arc-reactor
  │
  ├─ 2. DB에서 사용자 조회 + BCrypt 검증
  ├─ 3. 로컬 토큰 발급 (HS256)
  ▼
  └─ 4. 프로덕션과 동일한 토큰 형식
```

## 핵심 원칙

### 1. 인증은 aslan-iam만 한다
- 비밀번호 저장/검증은 aslan-iam 책임
- 각 서비스는 비밀번호를 직접 검증하지 않음 (개발 환경 제외)
- MFA, 소셜 로그인 등도 IAM에서 처리

### 2. 토큰 교환 패턴 (RFC 8693)
- IAM 토큰은 "신원 증명서" — 서비스에 직접 사용하지 않음
- 각 서비스가 IAM 토큰을 검증하고 로컬 토큰으로 교환
- 로컬 토큰에 서비스별 권한(role, scope, tenant) 포함

### 3. IAM 토큰을 직접 사용하지 않는 이유

| | IAM 토큰 직접 사용 | 토큰 교환 (현재 방식) |
|---|---|---|
| 서비스별 권한 | ❌ IAM에 모든 서비스 권한 넣어야 | ✅ 각 서비스가 자체 관리 |
| 토큰 크기 | ❌ 모든 서비스 권한 → 토큰 비대화 | ✅ 필요한 것만 포함 |
| 권한 변경 | ❌ IAM 재발급 필요 | ✅ 서비스 단독 변경 |
| 보안 분리 | ❌ IAM 키 유출 시 전체 노출 | ✅ 서비스별 키 독립 |
| 복잡도 | ✅ 단순 | ❌ exchange 엔드포인트 필요 |

## Arc Reactor 구현 현황

### 엔드포인트

| 엔드포인트 | 용도 | 환경 |
|-----------|------|------|
| `POST /api/auth/exchange` | IAM 토큰 → 로컬 토큰 교환 | 프로덕션 |
| `POST /api/auth/login` | 직접 로그인 (email + password) | 개발 |
| `POST /api/auth/register` | 사용자 등록 | 개발 (self-registration) |
| `POST /api/auth/logout` | 토큰 폐기 | 공통 |
| `GET /api/auth/me` | 현재 사용자 조회 | 공통 |

### 핵심 컴포넌트

```
arc-core/
  auth/
    AuthProvider          ← 인증 제공자 인터페이스
    DefaultAuthProvider   ← BCrypt 기반 직접 인증 (개발용)
    IamTokenExchangeService ← IAM RS256 검증 + 토큰 교환
    JwtTokenProvider      ← HS256 로컬 토큰 발급
    JwtAuthWebFilter      ← 요청마다 토큰 검증
    AdminInitializer      ← 서버 시작 시 초기 ADMIN 계정 생성
    UserStore             ← 사용자 CRUD (JDBC)
```

### 환경변수

```bash
# IAM 연동 (프로덕션)
ARC_REACTOR_AUTH_IAM_ENABLED=true
ARC_REACTOR_AUTH_IAM_ISSUER=https://iam.aslan.dev
ARC_REACTOR_AUTH_IAM_PUBLIC_KEY_URL=https://iam.aslan.dev/.well-known/jwks.json

# 직접 로그인 (개발)
ARC_REACTOR_AUTH_ADMIN_EMAIL=admin@arc.dev
ARC_REACTOR_AUTH_ADMIN_PASSWORD=admin1234

# JWT
ARC_REACTOR_AUTH_JWT_SECRET=<HS256 서명키>
ARC_REACTOR_AUTH_JWT_EXPIRY_HOURS=24

# 셀프 등록
ARC_REACTOR_AUTH_SELF_REGISTRATION_ENABLED=false
```

### Admin 프론트엔드 로그인 Flow

```typescript
// arc-reactor-admin/src/features/auth/context.tsx
async function attemptLogin(email, password) {
  // 1차: 직접 로그인 시도 (개발 환경 or IAM 프록시가 arc-reactor를 가리킬 때)
  const direct = await directLogin({ email, password })
  if (direct.token && direct.user) return direct

  // 2차: IAM 2-step (프로덕션)
  const iam = await login({ email, password })
  if (iam.accessToken) {
    const exchange = await exchangeToken(iam.accessToken)
    if (exchange.token && exchange.user) return exchange
  }
  return null
}
```

### Vite 프록시 설정

```
.env (개발):
  VITE_PROXY_TARGET=http://localhost:8080        # arc-reactor
  VITE_IAM_PROXY_TARGET=http://localhost:8080    # IAM 없으면 arc-reactor로

.env (프로덕션):
  VITE_PROXY_TARGET=https://api.arc-reactor.io
  VITE_IAM_PROXY_TARGET=https://iam.aslan.dev
```

## 다른 Aslan 서비스 적용 가이드

새 서비스에서 aslan-iam 연동 시:

### 1. 토큰 교환 엔드포인트 구현
```
POST /api/auth/exchange
Request:  { token: "IAM RS256 JWT" }
Response: { token: "서비스 HS256 JWT", user: { ... } }
```

### 2. IAM 공개키로 토큰 검증
- JWKS URL에서 RS256 공개키 조회 (캐싱 권장)
- `iss`, `exp`, `sub` 클레임 검증

### 3. 사용자 매핑
- IAM `sub` (UUID) → 서비스 로컬 User 매핑
- 첫 교환 시 자동 생성 (Just-In-Time Provisioning)

### 4. 서비스별 권한 부여
- 로컬 JWT에 서비스 전용 role/scope 포함
- 예: arc-reactor → `ADMIN | USER`, aslan-verse → `WORKSPACE_OWNER | MEMBER`

## 보안 고려사항

- IAM 토큰은 짧은 만료 (15분 권장) + refresh token으로 갱신
- 로컬 토큰은 서비스 특성에 맞게 (arc-reactor: 24시간)
- 직접 로그인은 프로덕션에서 비활성화 (`self-registration-enabled: false`)
- 토큰 폐기: `TokenRevocationStore`로 로그아웃 시 즉시 무효화
- Cross-tab 동기화: localStorage 변경 감지 → 자동 로그아웃
