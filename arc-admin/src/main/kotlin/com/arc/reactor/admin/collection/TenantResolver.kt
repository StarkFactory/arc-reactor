package com.arc.reactor.admin.collection

import com.arc.reactor.admin.tracing.TenantSpanProcessor
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.opentelemetry.context.Context
import org.springframework.core.Ordered
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

private val TENANT_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{1,64}$")
private const val TENANT_ID_INVALID_MSG =
    "Invalid tenant ID format. Only alphanumeric characters, hyphens, and underscores are allowed (max 64 chars)"

/**
 * 현재 테넌트 ID를 해석하는 resolver.
 *
 * 권장: [ServerWebExchange]를 사용하는 [resolveTenantId] — WebFlux에서 안정적 (ThreadLocal 미사용).
 * 레거시: ThreadLocal 기반 [currentTenantId] — 스레드 전환이 불가능한 경우에만 안전.
 *
 * 동반 [TenantWebFilter]가 해석된 테넌트를 exchange attribute (WebFlux-safe)와
 * ThreadLocal (레거시 호환) 양쪽에 저장한다.
 */
class TenantResolver {

    /** exchange attribute/헤더에서 테넌트 ID를 해석한다. WebFlux-safe — ThreadLocal 미사용. */
    fun resolveTenantId(exchange: ServerWebExchange): String {
        val resolvedTenantId = exchange.attributes[EXCHANGE_ATTR_KEY]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        val legacyTenantId = exchange.attributes[LEGACY_ATTR_KEY]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
        val tenantHeader = exchange.request.headers.getFirst(HEADER_NAME)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.also { validateTenantIdFormat(it) }

        val serverTenantId = resolvedTenantId ?: legacyTenantId
        if (serverTenantId != null && tenantHeader != null && serverTenantId != tenantHeader) {
            if (!canOverrideTenantContext(exchange)) {
                throw ServerWebInputException("Tenant header does not match resolved tenant context")
            }
            return tenantHeader
        }

        val effectiveTenantId = serverTenantId ?: tenantHeader
        if (effectiveTenantId != null) return effectiveTenantId
        return "default"
    }

    /** ThreadLocal 기반 테넌트 ID. **WebFlux reactive 체인에서 불안정** — 컨트롤러에서는 [resolveTenantId] 사용 권장. */
    fun currentTenantId(): String = TENANT_ID.get() ?: "default"

    fun setTenantId(tenantId: String) {
        TENANT_ID.set(tenantId)
    }

    fun clear() {
        TENANT_ID.remove()
    }

    companion object {
        private val TENANT_ID = ThreadLocal<String?>()
        const val HEADER_NAME = "X-Tenant-Id"
        const val EXCHANGE_ATTR_KEY = "resolvedTenantId"
        const val LEGACY_ATTR_KEY = "tenantId"
    }

    private fun validateTenantIdFormat(tenantId: String) {
        if (!TENANT_ID_PATTERN.matches(tenantId)) {
            throw ServerWebInputException(TENANT_ID_INVALID_MSG)
        }
    }

    private fun canOverrideTenantContext(exchange: ServerWebExchange): Boolean {
        val role = exchange.attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] as? UserRole
        return role?.isAnyAdmin() == true
    }
}

/**
 * 요청에서 테넌트 ID를 추출하여 [TenantResolver]에 설정하는 WebFilter.
 *
 * 요청 완료 후 정리하여 풀링된 스레드 환경에서 ThreadLocal 누수를 방지한다.
 * 최고 우선순위에서 실행되어 모든 하위 필터에서 테넌트를 사용할 수 있게 한다.
 */
class TenantWebFilter(
    private val tenantResolver: TenantResolver
) : WebFilter, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val tenantId = extractTenantId(exchange)
        tenantResolver.setTenantId(tenantId)
        exchange.attributes[TenantResolver.EXCHANGE_ATTR_KEY] = tenantId

        // TenantSpanProcessor를 위해 OTel Context에도 전파
        val otelScope = Context.current()
            .with(TenantSpanProcessor.TENANT_CONTEXT_KEY, tenantId)
            .makeCurrent()

        return chain.filter(exchange)
            .doFinally {
                otelScope.close()
                tenantResolver.clear()
            }
    }

    private fun extractTenantId(exchange: ServerWebExchange): String {
        return tenantResolver.resolveTenantId(exchange)
    }
}
