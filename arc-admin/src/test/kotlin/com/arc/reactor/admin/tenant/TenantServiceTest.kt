package com.arc.reactor.admin.tenant

import com.arc.reactor.admin.config.AdminProperties
import com.arc.reactor.admin.model.TenantPlan
import com.arc.reactor.admin.model.TenantStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TenantServiceTest {

    private lateinit var store: InMemoryTenantStore
    private lateinit var service: TenantService

    @BeforeEach
    fun setUp() {
        store = InMemoryTenantStore()
        service = TenantService(store, AdminProperties())
    }

    @Nested
    inner class Create {

        @Test
        fun `default quota로 create tenant해야 한다`() {
            val tenant = service.create("Acme Corp", "acme-corp", TenantPlan.STARTER)

            tenant.name shouldBe "Acme Corp"
            tenant.slug shouldBe "acme-corp"
            tenant.plan shouldBe TenantPlan.STARTER
            tenant.status shouldBe TenantStatus.ACTIVE
            tenant.quota.maxRequestsPerMonth shouldBe 10_000
            tenant.quota.maxTokensPerMonth shouldBe 10_000_000
        }

        @Test
        fun `reject blank name해야 한다`() {
            shouldThrow<IllegalArgumentException> {
                service.create("", "acme", TenantPlan.FREE)
            }.message shouldBe "Tenant name must not be blank"
        }

        @Test
        fun `reject invalid slug해야 한다`() {
            shouldThrow<IllegalArgumentException> {
                service.create("Acme", "ACME_CORP", TenantPlan.FREE)
            }.message shouldBe "Slug must contain only lowercase letters, numbers, and hyphens"
        }

        @Test
        fun `reject duplicate slug해야 한다`() {
            service.create("Acme 1", "acme-corp", TenantPlan.FREE)

            shouldThrow<IllegalArgumentException> {
                service.create("Acme 2", "acme-corp", TenantPlan.FREE)
            }.message shouldBe "Tenant with slug 'acme-corp' already exists"
        }
    }

    @Nested
    inner class UpdatePlan {

        @Test
        fun `update plan and quota해야 한다`() {
            val tenant = service.create("Acme", "acme-corp", TenantPlan.FREE)
            val updated = service.updatePlan(tenant.id, TenantPlan.BUSINESS)

            updated.plan shouldBe TenantPlan.BUSINESS
            updated.quota.maxRequestsPerMonth shouldBe 100_000
        }

        @Test
        fun `non-existent tenant에 대해 fail해야 한다`() {
            shouldThrow<IllegalArgumentException> {
                service.updatePlan("non-existent", TenantPlan.BUSINESS)
            }.message shouldBe "Tenant not found: non-existent"
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `suspend and activate tenant해야 한다`() {
            val tenant = service.create("Acme", "acme-corp", TenantPlan.FREE)

            val suspended = service.suspend(tenant.id)
            suspended.status shouldBe TenantStatus.SUSPENDED

            val activated = service.activate(tenant.id)
            activated.status shouldBe TenantStatus.ACTIVE
        }

        @Test
        fun `list tenants filtered by status해야 한다`() {
            service.create("Active 1", "active-1", TenantPlan.FREE)
            val t2 = service.create("Active 2", "active-2", TenantPlan.FREE)
            service.suspend(t2.id)

            val active = service.list(TenantStatus.ACTIVE)
            active.size shouldBe 1
            active[0].slug shouldBe "active-1"

            val suspended = service.list(TenantStatus.SUSPENDED)
            suspended.size shouldBe 1
            suspended[0].slug shouldBe "active-2"

            val all = service.list()
            all.size shouldBe 2
        }
    }

    @Nested
    inner class DefaultQuotas {

        @Test
        fun `have correct default quotas per plan해야 한다`() {
            val free = com.arc.reactor.admin.model.Tenant.defaultQuotaFor(TenantPlan.FREE)
            free.maxRequestsPerMonth shouldBe 1000
            free.maxUsers shouldBe 5

            val enterprise = com.arc.reactor.admin.model.Tenant.defaultQuotaFor(TenantPlan.ENTERPRISE)
            enterprise.maxRequestsPerMonth shouldBe Long.MAX_VALUE
        }
    }
}
