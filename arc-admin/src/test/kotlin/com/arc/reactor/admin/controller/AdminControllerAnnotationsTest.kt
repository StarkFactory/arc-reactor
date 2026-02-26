package com.arc.reactor.admin.controller

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.RestController

class AdminControllerAnnotationsTest {

    @Test
    fun `tenant admin controller should keep rest controller and conditional annotations`() {
        val type = TenantAdminController::class.java

        assertTrue(
            type.isAnnotationPresent(RestController::class.java),
            "TenantAdminController must have @RestController for request mapping registration"
        )
        assertTrue(
            type.isAnnotationPresent(ConditionalOnBean::class.java),
            "TenantAdminController must be conditional on DataSource availability"
        )
        val conditionalOnProperty = type.getAnnotation(ConditionalOnProperty::class.java)
        assertNotNull(
            conditionalOnProperty,
            "TenantAdminController must be gated by arc.reactor.admin.enabled"
        )
        assertEquals(
            "arc.reactor.admin",
            conditionalOnProperty.prefix,
            "TenantAdminController @ConditionalOnProperty prefix must be arc.reactor.admin"
        )
    }

    @Test
    fun `platform admin controller should keep rest controller and conditional annotations`() {
        val type = PlatformAdminController::class.java

        assertTrue(
            type.isAnnotationPresent(RestController::class.java),
            "PlatformAdminController must have @RestController for request mapping registration"
        )
        assertTrue(
            type.isAnnotationPresent(ConditionalOnBean::class.java),
            "PlatformAdminController must be conditional on DataSource availability"
        )
        val conditionalOnProperty = type.getAnnotation(ConditionalOnProperty::class.java)
        assertNotNull(
            conditionalOnProperty,
            "PlatformAdminController must be gated by arc.reactor.admin.enabled"
        )
        assertEquals(
            "arc.reactor.admin",
            conditionalOnProperty.prefix,
            "PlatformAdminController @ConditionalOnProperty prefix must be arc.reactor.admin"
        )
    }

    @Test
    fun `metric ingestion controller should keep rest controller and admin toggle`() {
        val type = MetricIngestionController::class.java

        assertTrue(
            type.isAnnotationPresent(RestController::class.java),
            "MetricIngestionController must have @RestController for request mapping registration"
        )

        val conditionalOnProperty = type.getAnnotation(ConditionalOnProperty::class.java)
        assertNotNull(
            conditionalOnProperty,
            "MetricIngestionController must be gated by arc.reactor.admin.enabled"
        )
        assertEquals(
            "arc.reactor.admin",
            conditionalOnProperty.prefix,
            "MetricIngestionController @ConditionalOnProperty prefix must be arc.reactor.admin"
        )
    }
}
