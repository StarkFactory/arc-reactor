# Arc Reactor 알림 룰 가이드

Prometheus 알림 룰 설명 및 대응 런북.

## 설치

```bash
# Prometheus 설정에 룰 파일 추가
# prometheus.yml
rule_files:
  - "prometheus-rules.yml"
```

Grafana 대시보드는 `docs/operations/grafana/` 디렉토리의 JSON 파일을 Grafana UI에서 Import하여 사용.

---

## 에이전트 알림

### AgentSuccessRateLow

| 항목 | 내용 |
|------|------|
| 심각도 | critical |
| 조건 | 에이전트 실패율 > 15% (5분 지속) |
| 담당 | ai-platform |

**원인 분석:**
1. LLM 프로바이더 장애 (API 응답 오류, rate limit)
2. 도구 실행 실패 연쇄 (외부 API 다운)
3. Guard 거부율 급증 (잘못된 패턴 매칭)

**대응:**
1. `arc.agent.execution` 메트릭에서 `result` 레이블로 실패 유형 확인
2. LLM 프로바이더 상태 페이지 확인
3. Guard 거부 로그 확인: `grep "guard.rejected" /var/log/arc-reactor/app.log`
4. 필요 시 `CostAwareModelRouter` 폴백 모델 확인

**에스컬레이션:** 15분 이상 지속 시 온콜 엔지니어에게 전달.

---

### AgentResponseTimeSlow

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | 평균 응답 시간 > 30초 (5분 지속) |
| 담당 | ai-platform |

**원인 분석:**
1. ReAct 루프 스텝 수 증가 (복잡한 쿼리)
2. 도구 응답 지연 (외부 API 느림)
3. LLM 응답 지연 (프로바이더 부하)

**대응:**
1. `arc_agent_execution_steps` 분포 확인 -- 스텝 수가 비정상적으로 높은지 확인
2. 도구별 지연 확인: `arc_agent_tool_duration_seconds` 메트릭
3. `maxToolCalls` 설정 확인 (기본값: 10)
4. 프로바이더별 응답 시간 비교

**에스컬레이션:** 응답 시간 60초 초과 시 `AgentResponseTimeCritical` 알림이 발동됨.

---

### AgentResponseTimeCritical

| 항목 | 내용 |
|------|------|
| 심각도 | critical |
| 조건 | 평균 응답 시간 > 60초 (3분 지속) |
| 담당 | ai-platform |

**대응:**
1. `AgentResponseTimeSlow`의 대응 절차를 먼저 수행
2. 활성 에이전트 실행 수 확인: `arc.reactor.concurrency.max-concurrent-requests`
3. 필요 시 요청 제한 강화
4. LLM 프로바이더 전환 고려

**에스컬레이션:** 즉시 온콜 엔지니어에게 전달. 사용자 영향 범위 파악 후 공지.

---

## 비용 알림

### HighHourlyCost

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | 시간당 비용 > $50 (10분 지속) |
| 담당 | ai-platform |

**원인 분석:**
1. 트래픽 급증 (정상적 증가 vs 비정상)
2. ReAct 루프 과다 (스텝 수 증가)
3. 고비용 모델 집중 사용

**대응:**
1. `arc_agent_request_cost` 메트릭에서 모델별 비용 분포 확인
2. 요청 패턴 확인 -- 특정 사용자/테넌트 집중 여부
3. `CostAwareModelRouter` 설정 확인 및 저비용 모델로 라우팅 조정
4. `StepBudgetTracker` 예산 한도 조정 고려

**에스컬레이션:** 시간당 $100 초과 시 팀 리드에게 알림.

---

### HighDailyCost

| 항목 | 내용 |
|------|------|
| 심각도 | critical |
| 조건 | 일일 누적 비용 > $500 (30분 지속) |
| 담당 | ai-platform |

**대응:**
1. `HighHourlyCost`의 분석 절차를 먼저 수행
2. `budget.max-tokens-per-request` 설정으로 요청당 토큰 제한 적용
3. 비필수 도구 비활성화 고려
4. 비용 추세 분석 -- 월말 예산 초과 가능성 평가

**에스컬레이션:** 즉시 팀 리드 + 관리자에게 보고. 비용 한도 정책 재검토.

---

## 도구 알림

### ToolFailureRateHigh

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | 특정 도구 실패율 > 30% (5분 지속) |
| 담당 | ai-platform |

**대응:**
1. 실패하는 도구 식별: `tool` 레이블 확인
2. 도구 로그 확인: 외부 API 오류, 인증 만료, 타임아웃 등
3. MCP 서버 연결 상태 확인 (MCP 도구인 경우)
4. 필요 시 해당 도구 비활성화: `tool-filter.enabled=true`로 필터링

**에스컬레이션:** 핵심 도구(검색, DB 조회 등) 실패 시 즉시 대응.

---

### ToolTimeoutFrequent

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | 도구 평균 응답 시간 > 10초 (5분 지속) |
| 담당 | ai-platform |

**대응:**
1. 지연되는 도구 식별
2. 외부 API 응답 시간 확인
3. `concurrency.tool-call-timeout-ms` 설정 확인 (기본값: 15000ms)
4. 네트워크 지연 여부 확인

---

## 보안 알림

### GuardRejectionSpike

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | Guard 거부율 > 분당 60건 (5분 지속) |
| 담당 | security |

**원인 분석:**
1. 프롬프트 인젝션 공격 시도
2. Guard 패턴의 false positive 급증
3. 새 배포 후 정상 요청이 거부되는 경우

**대응:**
1. `arc_agent_guard_rejections_total`에서 `stage` 레이블로 거부 단계 확인
2. 거부된 요청 로그 분석 -- 공격 패턴 vs false positive 구분
3. false positive인 경우 `InjectionPatterns.kt` 패턴 조정
4. 공격인 경우 IP 기반 rate limit 강화

**에스컬레이션:** 공격 확인 시 보안팀에 즉시 보고.

---

### GuardRejectionCritical

| 항목 | 내용 |
|------|------|
| 심각도 | critical |
| 조건 | Guard 거부율 > 분당 300건 (3분 지속) |
| 담당 | security |

**대응:**
1. `GuardRejectionSpike`의 분석 절차를 먼저 수행
2. WAF/방화벽 로그와 교차 분석
3. 필요 시 의심 IP 차단
4. rate limit 임계값 하향 조정: `guard.rate-limit-per-minute`

**에스컬레이션:** 즉시 보안팀 + 온콜 엔지니어에게 전달.

---

## 인프라 알림

### HighHeapUsage

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | JVM 힙 사용률 > 85% (5분 지속) |
| 담당 | platform |

**대응:**
1. GC 로그 확인 -- Full GC 빈도 및 소요 시간
2. 메모리 누수 여부 확인: 힙 덤프 분석
3. 동시 요청 수 확인 -- 부하 증가 여부
4. JVM 힙 크기 증가 고려: `-Xmx` 조정

**에스컬레이션:** 90% 초과 시 즉시 대응. OOM 발생 전 조치.

---

### HikariConnectionPoolExhausted

| 항목 | 내용 |
|------|------|
| 심각도 | critical |
| 조건 | 대기 커넥션 > 5개 (3분 지속) |
| 담당 | platform |

**대응:**
1. DB 슬로우 쿼리 확인
2. 커넥션 풀 크기 확인: `spring.datasource.hikari.maximum-pool-size`
3. 활성 트랜잭션 확인 -- 장기 실행 쿼리 존재 여부
4. DB 서버 부하 확인

**에스컬레이션:** 커넥션 고갈 시 서비스 장애로 이어짐. 즉시 대응.

---

### HighHttpErrorRate

| 항목 | 내용 |
|------|------|
| 심각도 | critical |
| 조건 | HTTP 5xx 에러율 > 5% (5분 지속) |
| 담당 | platform |

**대응:**
1. 에러 로그에서 5xx 원인 확인
2. 특정 엔드포인트 집중 여부 확인
3. 업스트림 서비스 (LLM 프로바이더, DB) 상태 확인
4. 최근 배포 이력 확인 -- 롤백 필요 여부 판단

**에스컬레이션:** 10% 초과 시 즉시 롤백 검토.

---

### HighHttpLatency

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | HTTP p99 지연 > 5초 (5분 지속) |
| 담당 | platform |

**대응:**
1. 지연이 높은 엔드포인트 확인
2. 에이전트 처리 시간 vs 네트워크 지연 구분
3. DB 쿼리 성능 확인
4. GC 영향 확인

---

## 캐시 알림

### CacheHitRateLow

| 항목 | 내용 |
|------|------|
| 심각도 | warning |
| 조건 | 캐시 히트율 < 30% (15분 지속) |
| 담당 | ai-platform |

**대응:**
1. 캐시 키 분포 확인 -- 고유 요청이 많은지 확인
2. 캐시 TTL 설정 확인
3. 캐시 크기 제한 확인 -- eviction 빈도
4. 요청 패턴 변화 여부 확인

---

## 임계값 조정 가이드

위 임계값은 300명 규모 조직 기준 초기 설정입니다. 운영 데이터 축적 후 조정을 권장합니다.

| 알림 | 기본 임계값 | 조정 기준 |
|------|------------|----------|
| AgentSuccessRateLow | 실패율 15% | 사용 패턴에 따라 10-20% 범위 |
| HighHourlyCost | $50/h | 예산 및 트래픽에 따라 조정 |
| HighDailyCost | $500/day | 월 예산의 1/30 기준 |
| ToolFailureRateHigh | 30% | 도구 특성에 따라 개별 조정 |
| GuardRejectionSpike | 60건/min | 트래픽 규모에 비례 |
| HighHeapUsage | 85% | JVM 설정에 따라 조정 |
| CacheHitRateLow | 30% | 캐시 전략에 따라 조정 |
