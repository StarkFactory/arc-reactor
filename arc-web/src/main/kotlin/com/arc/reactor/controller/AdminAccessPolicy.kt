package com.arc.reactor.controller

import com.arc.reactor.auth.UserRole

/**
 * RBAC policy for admin API access.
 *
 * Keeping the rule here separates access policy from controller transport code.
 */
object AdminAccessPolicy {
    fun isAdmin(role: UserRole?): Boolean = role == UserRole.ADMIN
}
