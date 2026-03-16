package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyProperties
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * 런타임 유효 도구 정책 제공자
 *
 * 런타임에 현재 유효한 도구 정책을 제공한다.
 *
 * ## 정책 해석 우선순위
 * 1. `arc.reactor.tool-policy.enabled=false` → 비활성화된 정책 반환 (저장소 값 무관)
 * 2. 동적 정책 비활성화 → application.yml 속성에서 정책 생성
 * 3. 동적 정책 활성화 → 저장소에서 로드 (TTL 캐시 사용)
 *
 * ## 캐시 전략
 * - [AtomicReference]로 캐시된 정책을 보관한다
 * - [AtomicLong]으로 마지막 캐시 시각을 추적한다
 * - TTL 이내이면 캐시를 반환하고, 초과하면 저장소에서 새로 로드한다
 * - 로드 실패 시 마지막 캐시 또는 속성 기반 폴백을 사용한다 (fail-soft)
 *
 * ## 정규화
 * 모든 정책 값을 로드 시 정규화한다:
 * - 도구 이름: 공백 제거, 빈 문자열 필터링
 * - 채널 이름: 소문자 변환, 공백 제거
 * - 거부 메시지: 빈 문자열이면 속성의 기본값 사용
 *
 * @param properties application.yml의 도구 정책 속성
 * @param store 동적 정책 저장소
 *
 * @see ToolPolicy 도구 정책 데이터 클래스
 * @see ToolPolicyStore 동적 정책 저장소 인터페이스
 * @see ToolExecutionPolicyEngine 이 Provider를 사용하는 정책 엔진
 */
class ToolPolicyProvider(
    private val properties: ToolPolicyProperties,
    private val store: ToolPolicyStore
) {

    /** 캐시된 정책 */
    private val cached = AtomicReference<ToolPolicy?>(null)

    /** 마지막 캐시 갱신 시각 (밀리초) */
    private val cachedAtMs = AtomicLong(0)

    /** 캐시를 무효화하여 다음 호출 시 저장소에서 새로 로드하게 한다 */
    fun invalidate() {
        cachedAtMs.set(0)
        cached.set(null)
    }

    /**
     * 현재 유효한 도구 정책을 반환한다.
     * 캐시 TTL이 유효하면 캐시를 반환하고, 아니면 저장소에서 새로 로드한다.
     */
    fun current(): ToolPolicy {
        // 마스터 스위치: 비활성화되면 빈 정책 반환
        if (!properties.enabled) {
            return ToolPolicy(
                enabled = false,
                writeToolNames = emptySet(),
                denyWriteChannels = emptySet(),
                allowWriteToolNamesInDenyChannels = emptySet(),
                allowWriteToolNamesByChannel = emptyMap(),
                denyWriteMessage = properties.denyWriteMessage,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH
            )
        }

        // 동적 정책 비활성화 → 속성에서 직접 생성
        if (!properties.dynamic.enabled) {
            return normalize(ToolPolicy.fromProperties(properties))
        }

        // 동적 정책 활성화 → 캐시 확인 후 저장소에서 로드
        val now = System.currentTimeMillis()
        val ttlMs = properties.dynamic.refreshMs.coerceAtLeast(250)
        val cachedAt = cachedAtMs.get()
        val existing = cached.get()
        if (existing != null && now - cachedAt < ttlMs) return existing

        // 저장소에서 로드, 실패 시 마지막 캐시/속성으로 폴백 (fail-soft)
        return runCatching {
            val loaded = store.getOrNull() ?: ToolPolicy.fromProperties(properties)
            val normalized = normalize(loaded)
            cached.set(normalized)
            cachedAtMs.set(now)
            normalized
        }.getOrElse { e ->
            logger.warn(e) { "Failed to load dynamic tool policy; falling back to cached/properties" }
            existing ?: normalize(ToolPolicy.fromProperties(properties))
        }
    }

    /**
     * 정책 값을 정규화한다.
     * - 도구 이름: 공백 제거, 빈 문자열 필터링
     * - 채널 이름: 소문자 변환, 공백 제거
     * - 거부 메시지: 빈 문자열이면 속성의 기본값 사용
     */
    private fun normalize(policy: ToolPolicy): ToolPolicy {
        return policy.copy(
            writeToolNames = policy.writeToolNames.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
            denyWriteChannels = policy.denyWriteChannels
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet(),
            allowWriteToolNamesInDenyChannels = policy.allowWriteToolNamesInDenyChannels
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet(),
            allowWriteToolNamesByChannel = policy.allowWriteToolNamesByChannel
                .mapKeys { (k, _) -> k.trim().lowercase() }
                .mapValues { (_, v) -> v.map { it.trim() }.filter { it.isNotBlank() }.toSet() }
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotEmpty() },
            denyWriteMessage = policy.denyWriteMessage.ifBlank { properties.denyWriteMessage }
        )
    }
}
