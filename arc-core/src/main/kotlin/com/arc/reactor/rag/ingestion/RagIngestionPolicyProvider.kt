package com.arc.reactor.rag.ingestion

import com.arc.reactor.agent.config.RagIngestionProperties
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * 런타임에 유효한 RAG 수집 정책을 제공하는 프로바이더.
 *
 * 정적(properties) 설정과 동적(DB 저장소) 설정을 통합하며,
 * TTL 기반 캐싱으로 DB 조회 빈도를 줄인다.
 *
 * ## 정책 해소 우선순위
 * 1. `properties.enabled == false` → 즉시 비활성 정책 반환
 * 2. `properties.dynamic.enabled == false` → properties에서 정적 정책 생성
 * 3. 동적 모드: DB 저장소에서 로드하고 TTL로 캐싱. 실패 시 캐시/properties로 폴백.
 *
 * @param properties application.yml에서 가져온 수집 설정
 * @param store DB 기반 정책 저장소
 */
class RagIngestionPolicyProvider(
    private val properties: RagIngestionProperties,
    private val store: RagIngestionPolicyStore
) {

    /** 캐싱된 정책. null이면 아직 로드하지 않았거나 무효화됨. */
    private val cached = AtomicReference<RagIngestionPolicy?>(null)
    /** 캐시가 갱신된 시각 (epoch ms) */
    private val cachedAtMs = AtomicLong(0)

    /** 캐시를 수동으로 무효화한다. 정책이 변경되었을 때 호출. */
    fun invalidate() {
        cached.set(null)
        cachedAtMs.set(0)
    }

    /**
     * 현재 유효한 정책을 반환한다.
     *
     * 동적 모드에서는 TTL이 만료되면 DB에서 새로 로드하고,
     * 로드 실패 시 이전 캐시값 또는 properties 기본값으로 폴백한다.
     */
    fun current(): RagIngestionPolicy {
        // 수집 기능 자체가 비활성이면 즉시 반환
        if (!properties.enabled) {
            return RagIngestionPolicy(
                enabled = false,
                requireReview = properties.requireReview,
                allowedChannels = emptySet(),
                minQueryChars = properties.minQueryChars.coerceAtLeast(1),
                minResponseChars = properties.minResponseChars.coerceAtLeast(1),
                blockedPatterns = emptySet(),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH
            )
        }

        // 동적 정책이 비활성이면 properties에서 정적 정책 생성
        if (!properties.dynamic.enabled) {
            return normalize(RagIngestionPolicy.fromProperties(properties))
        }

        // TTL 기반 캐시 확인
        val now = System.currentTimeMillis()
        val ttlMs = properties.dynamic.refreshMs.coerceAtLeast(250)
        val cachedValue = cached.get()
        val cachedAt = cachedAtMs.get()
        if (cachedValue != null && now - cachedAt < ttlMs) return cachedValue

        // DB에서 로드 시도, 실패 시 안전하게 폴백
        return runCatching {
            val loaded = store.getOrNull() ?: RagIngestionPolicy.fromProperties(properties)
            val normalized = normalize(loaded)
            cached.set(normalized)
            cachedAtMs.set(now)
            normalized
        }.getOrElse { e ->
            logger.warn(e) { "Failed to load dynamic rag ingestion policy; falling back to cached/properties" }
            cachedValue ?: normalize(RagIngestionPolicy.fromProperties(properties))
        }
    }

    /**
     * 정책 값을 정규화한다.
     * - 채널명 소문자 변환 및 공백 제거
     * - 최소 문자 수 1 이상 보장
     * - 빈 패턴 필터링
     */
    private fun normalize(policy: RagIngestionPolicy): RagIngestionPolicy {
        return policy.copy(
            allowedChannels = policy.allowedChannels.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet(),
            minQueryChars = policy.minQueryChars.coerceAtLeast(1),
            minResponseChars = policy.minResponseChars.coerceAtLeast(1),
            blockedPatterns = policy.blockedPatterns.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        )
    }
}
