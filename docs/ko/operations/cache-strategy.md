# Arc Reactor 캐시 전략

## 원칙

- **Redis를 1차 캐시**로 사용 (분산 환경 대응)
- Caffeine(로컬)은 Redis 불가 시 폴백 또는 초경량 캐시에만 사용
- 모든 Redis 키는 통일된 네이밍 규격을 따름

## Redis 키 네이밍 규격

```
arc:{domain}:{purpose}:{identifier}
```

| 세그먼트 | 설명 | 예시 |
|---------|------|------|
| `arc` | 프로젝트 접두사 (항상 고정) | `arc` |
| `{domain}` | 기능 도메인 | `auth`, `cache`, `settings`, `slack` |
| `{purpose}` | 세부 목적 | `revoked`, `response`, `name` |
| `{identifier}` | 개별 식별자 | 토큰ID, 사용자ID, 설정키 등 |

### 현재 사용 중인 키 패턴

| 키 패턴 | 도메인 | TTL | 용도 |
|---------|--------|-----|------|
| `arc:auth:revoked:{tokenId}` | auth | JWT 만료시간 | 토큰 폐기 |
| `arc:cache:entry:{hash}` | cache | 설정값 | 시맨틱 응답 캐시 |
| `arc:cache:index:{hash}` | cache | 설정값 | 시맨틱 캐시 인덱스 |
| `arc:settings:{key}` | settings | 30초 | 런타임 설정 캐시 |
| `arc:slack:name:{userId}` | slack | 1시간 | 사용자 표시 이름 |

## TTL 가이드

| 데이터 특성 | TTL | 예시 |
|------------|-----|------|
| 자주 변하지 않는 참조 데이터 | 1시간~24시간 | 사용자 이름, 채널 정보 |
| 설정값 | 30초~5분 | 런타임 설정 |
| 세션/인증 | JWT 만료 시간 | 토큰 폐기 |
| 응답 캐시 | 설정 가능 (기본 5분) | 시맨틱 캐시 |

## 사용 패턴

### 기본 패턴 (get-or-load)

```kotlin
fun getValue(key: String): String? {
    // 1. Redis에서 조회
    val cached = redisTemplate.opsForValue().get("arc:domain:$key")
    if (cached != null) return cached

    // 2. 원본에서 로드
    val value = loadFromSource(key) ?: return null

    // 3. Redis에 캐시
    redisTemplate.opsForValue().set("arc:domain:$key", value, Duration.ofSeconds(ttl))
    return value
}
```

### 무효화 패턴

```kotlin
fun invalidate(key: String) {
    redisTemplate.delete("arc:domain:$key")
}

fun invalidateAll(pattern: String) {
    // SCAN으로 패턴 매칭 삭제 (KEYS 사용 금지)
    redisTemplate.scan(ScanOptions.scanOptions().match(pattern).build()).use { cursor ->
        cursor.forEach { redisTemplate.delete(it) }
    }
}
```

## 주의사항

- **KEYS 명령 사용 금지** — 프로덕션에서 블로킹 위험. SCAN 사용
- **TTL 필수** — 모든 키에 TTL 설정. 무한 캐시 금지
- **직렬화** — 문자열 기본. 복잡한 객체는 JSON 직렬화
- **Redis 장애 시** — 캐시 미스로 처리 (DB 직접 조회). 애플리케이션 장애로 전파하지 않음
- **네임스페이스 충돌** — 새 키 패턴 추가 시 이 문서에 등록
