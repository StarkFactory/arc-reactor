package com.arc.reactor.agent.config

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * tool-routing.yml 라우트 설정의 시작 시 유효성 검증기.
 *
 * 애플리케이션 시작 시 모든 라우트를 검증하여 누락·오타로 인한
 * 무음 품질 저하를 방지한다.
 *
 * - `id`가 빈 라우트가 하나라도 있으면 예외를 발생시킨다 (fail-fast).
 * - 매칭 조건(`keywords`, `regexPatternRef`, `multiKeywordGroups`, `parentRoute`)이 모두 없으면 경고한다.
 * - `promptInstruction`이 비어있으면 경고한다.
 * - `category`가 알려진 카테고리 목록에 없으면 경고한다.
 * - `preferredTools`가 비어있으면 정보 로그를 남긴다.
 */
object ToolRoutingConfigValidator {

    private val KNOWN_CATEGORIES = setOf(
        "confluence", "work", "workContext", "jira", "bitbucket", "swagger"
    )

    /**
     * 주어진 라우팅 설정의 모든 라우트를 검증한다.
     *
     * @param config 검증할 라우팅 설정
     * @throws IllegalStateException id가 빈 라우트가 있을 때
     */
    fun validate(config: ToolRoutingConfig) {
        val routes = config.routes
        if (routes.isEmpty()) {
            logger.info { "tool-routing.yml: no routes defined" }
            return
        }

        var errorCount = 0
        var warnCount = 0

        for ((index, route) in routes.withIndex()) {
            if (route.id.isBlank()) {
                logger.error { "route[$index]: id is empty — this route will be skipped" }
                errorCount++
                continue
            }

            if (route.category.isBlank()) {
                logger.warn { "route '${route.id}': category is empty" }
                warnCount++
            } else if (route.category !in KNOWN_CATEGORIES) {
                logger.warn {
                    "route '${route.id}': unknown category '${route.category}' " +
                        "(known: $KNOWN_CATEGORIES)"
                }
                warnCount++
            }

            val hasMatchCondition = route.keywords.isNotEmpty() ||
                route.regexPatternRef != null ||
                route.multiKeywordGroups.isNotEmpty() ||
                route.parentRoute != null
            if (!hasMatchCondition) {
                logger.warn {
                    "route '${route.id}': no match condition defined " +
                        "(keywords, regexPatternRef, multiKeywordGroups, or parentRoute) — " +
                        "this route will never match"
                }
                warnCount++
            }

            if (route.promptInstruction.isBlank()) {
                logger.warn { "route '${route.id}': promptInstruction is empty" }
                warnCount++
            }

            if (route.preferredTools.isEmpty()) {
                logger.info {
                    "route '${route.id}': preferredTools is empty — " +
                        "falls back to semantic tool selection"
                }
            }
        }

        if (errorCount > 0) {
            throw IllegalStateException(
                "tool-routing.yml validation failed: $errorCount route(s) have empty id"
            )
        }

        logger.info {
            "tool-routing.yml: validated ${routes.size} routes " +
                "($warnCount warning(s))"
        }
    }
}
