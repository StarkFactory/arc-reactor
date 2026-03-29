package com.arc.reactor.admin.tenant

import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantStatus
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * 테넌트 저장소 인터페이스.
 *
 * @see JdbcTenantStore JDBC 기반 구현
 * @see InMemoryTenantStore 인메모리 구현
 */
interface TenantStore {
    fun findById(id: String): Tenant?
    fun findBySlug(slug: String): Tenant?
    fun findAll(status: TenantStatus? = null): List<Tenant>
    fun save(tenant: Tenant): Tenant
    fun delete(id: String): Boolean
}

/** Caffeine 기반 인메모리 [TenantStore] 구현체. */
class InMemoryTenantStore : TenantStore {
    private val tenants = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build<String, Tenant>()

    override fun findById(id: String): Tenant? = tenants.getIfPresent(id)

    override fun findBySlug(slug: String): Tenant? =
        tenants.asMap().values.firstOrNull { it.slug == slug }

    override fun findAll(status: TenantStatus?): List<Tenant> =
        tenants.asMap().values
            .filter { status == null || it.status == status }
            .sortedByDescending { it.createdAt }

    override fun save(tenant: Tenant): Tenant {
        tenants.put(tenant.id, tenant)
        return tenant
    }

    override fun delete(id: String): Boolean = tenants.asMap().remove(id) != null
}
