package com.arc.reactor.admin.tenant

import com.arc.reactor.admin.model.Tenant
import com.arc.reactor.admin.model.TenantStatus
import java.util.concurrent.ConcurrentHashMap

interface TenantStore {
    fun findById(id: String): Tenant?
    fun findBySlug(slug: String): Tenant?
    fun findAll(status: TenantStatus? = null): List<Tenant>
    fun save(tenant: Tenant): Tenant
    fun delete(id: String): Boolean
}

class InMemoryTenantStore : TenantStore {
    private val tenants = ConcurrentHashMap<String, Tenant>()

    override fun findById(id: String): Tenant? = tenants[id]

    override fun findBySlug(slug: String): Tenant? =
        tenants.values.firstOrNull { it.slug == slug }

    override fun findAll(status: TenantStatus?): List<Tenant> =
        tenants.values
            .filter { status == null || it.status == status }
            .sortedByDescending { it.createdAt }

    override fun save(tenant: Tenant): Tenant {
        tenants[tenant.id] = tenant
        return tenant
    }

    override fun delete(id: String): Boolean = tenants.remove(id) != null
}
