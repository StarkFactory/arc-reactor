package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditLog
import com.arc.reactor.audit.AdminAuditStore
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun recordAdminAudit(
    store: AdminAuditStore,
    category: String,
    action: String,
    actor: String,
    resourceType: String? = null,
    resourceId: String? = null,
    detail: String? = null
) {
    runCatching {
        store.save(
            AdminAuditLog(
                category = category,
                action = action,
                actor = actor,
                resourceType = resourceType,
                resourceId = resourceId,
                detail = detail
            )
        )
    }.onFailure { e ->
        logger.warn(e) { "Failed to persist admin audit log: category=$category action=$action resourceId=$resourceId" }
    }
}
