package com.arc.reactor.admin.tenant

import com.arc.reactor.admin.config.AdminProperties
import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 테넌트 생명주기 관리 서비스.
 *
 * 생성, 조회, 플랜 변경, 정지, 활성화 등의 비즈니스 로직을 담당한다.
 * slug 중복 검증과 플랜별 기본 쿼터 적용을 수행한다.
 *
 * @see TenantStore 테넌트 저장소
 * @see PlatformAdminController 이 서비스를 사용하는 컨트롤러
 */
class TenantService(
    private val tenantStore: TenantStore,
    private val properties: AdminProperties
) {

    /** 신규 테넌트를 생성한다. slug 중복 시 예외를 던진다. */
    fun create(name: String, slug: String, plan: TenantPlan, billingEmail: String? = null): Tenant {
        require(name.isNotBlank()) { "테넌트 이름은 비어 있을 수 없습니다" }
        require(slug.isNotBlank()) { "테넌트 slug는 비어 있을 수 없습니다" }
        require(SLUG_PATTERN.matches(slug)) { "slug는 소문자, 숫자, 하이픈만 허용됩니다" }

        val existing = tenantStore.findBySlug(slug)
        require(existing == null) { "slug '$slug'는 이미 사용 중입니다" }

        val tenant = Tenant(
            name = name,
            slug = slug,
            plan = plan,
            quota = Tenant.defaultQuotaFor(plan),
            billingEmail = billingEmail,
            sloAvailability = properties.slo.defaultAvailability,
            sloLatencyP99Ms = properties.slo.defaultLatencyP99Ms
        )

        val saved = tenantStore.save(tenant)
        logger.info { "테넌트 생성: id=${saved.id}, slug=$slug, plan=$plan" }
        return saved
    }

    fun getById(id: String): Tenant? = tenantStore.findById(id)

    fun getBySlug(slug: String): Tenant? = tenantStore.findBySlug(slug)

    fun list(status: TenantStatus? = null): List<Tenant> = tenantStore.findAll(status)

    /** 테넌트 플랜을 변경하고 기본 쿼터를 재적용한다. */
    fun updatePlan(id: String, plan: TenantPlan): Tenant {
        val tenant = tenantStore.findById(id)
            ?: throw IllegalArgumentException("테넌트를 찾을 수 없습니다: $id")

        val updated = tenant.copy(
            plan = plan,
            quota = Tenant.defaultQuotaFor(plan),
            updatedAt = Instant.now()
        )
        tenantStore.save(updated)
        logger.info { "테넌트 플랜 변경: id=$id, plan=$plan" }
        return updated
    }

    /** 테넌트를 정지 상태로 변경한다. */
    fun suspend(id: String): Tenant {
        val tenant = tenantStore.findById(id)
            ?: throw IllegalArgumentException("테넌트를 찾을 수 없습니다: $id")

        val updated = tenant.copy(status = TenantStatus.SUSPENDED, updatedAt = Instant.now())
        tenantStore.save(updated)
        logger.info { "테넌트 정지: id=$id" }
        return updated
    }

    /** 테넌트를 활성 상태로 변경한다. */
    fun activate(id: String): Tenant {
        val tenant = tenantStore.findById(id)
            ?: throw IllegalArgumentException("테넌트를 찾을 수 없습니다: $id")

        val updated = tenant.copy(status = TenantStatus.ACTIVE, updatedAt = Instant.now())
        tenantStore.save(updated)
        logger.info { "테넌트 활성화: id=$id" }
        return updated
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$")
    }
}
