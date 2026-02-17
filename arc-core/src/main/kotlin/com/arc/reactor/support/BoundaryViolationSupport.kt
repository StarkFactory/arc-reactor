package com.arc.reactor.support

/**
 * Standard boundary violation message formatter.
 */
fun formatBoundaryViolation(
    violation: String,
    policy: String,
    limit: Int,
    actual: Int
): String = "Boundary violation [$violation]: policy=$policy, limit=$limit, actual=$actual"

/**
 * Standard boundary rule message formatter for guard input validation.
 */
fun formatBoundaryRuleViolation(
    rule: String,
    actual: Int,
    limit: Int
): String = "Boundary violation [$rule]: actual=$actual, limit=$limit"
