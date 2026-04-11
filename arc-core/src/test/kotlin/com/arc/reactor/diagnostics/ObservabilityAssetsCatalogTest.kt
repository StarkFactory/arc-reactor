package com.arc.reactor.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ObservabilityAssetsCatalog] R261 단위 테스트.
 *
 * 정적 카탈로그의 무결성과 cold-start 운영자가 활용할 수 있는 핵심 정보가
 * 누락 없이 제공되는지 확인한다.
 */
class ObservabilityAssetsCatalogTest {

    @Test
    fun `카탈로그는 R256 R259 R260 3개 자산을 포함해야 한다`() {
        val all = ObservabilityAssetsCatalog.all
        assertEquals(3, all.size) { "R256+R259+R260 = 3개 자산" }

        val rounds = all.map { it.round }.toSet()
        assertTrue(rounds.contains("R256")) { "R256 (운영 플레이북) 포함" }
        assertTrue(rounds.contains("R259")) { "R259 (Grafana 대시보드) 포함" }
        assertTrue(rounds.contains("R260")) { "R260 (Alertmanager 규칙) 포함" }
    }

    @Test
    fun `kind 필드는 playbook dashboard alerts 3종이어야 한다`() {
        val kinds = ObservabilityAssetsCatalog.all.map { it.kind }.toSet()
        assertEquals(setOf("playbook", "dashboard", "alerts"), kinds) {
            "3종 자산 kind 일치"
        }
    }

    @Test
    fun `R256 playbook은 evaluation-metrics md 경로를 가져야 한다`() {
        val playbook = ObservabilityAssetsCatalog.playbook
        assertEquals("R256", playbook.round)
        assertEquals("playbook", playbook.kind)
        assertTrue(playbook.path.contains("docs/evaluation-metrics.md")) {
            "R256 자산 경로에 evaluation-metrics.md 포함"
        }
        assertTrue(playbook.description.isNotBlank()) { "description 비어있지 않음" }
        assertTrue(playbook.importInstructions.isNotBlank()) {
            "importInstructions 비어있지 않음"
        }
    }

    @Test
    fun `R259 dashboard는 Grafana 안내를 포함해야 한다`() {
        val dashboard = ObservabilityAssetsCatalog.dashboard
        assertEquals("R259", dashboard.round)
        assertEquals("dashboard", dashboard.kind)
        assertTrue(dashboard.description.contains("패널")) { "패널 정보 포함" }
        assertTrue(dashboard.importInstructions.contains("Grafana")) {
            "import 지침에 Grafana 언급"
        }
    }

    @Test
    fun `R260 alerts는 alertmanager-rules yaml 경로와 alert 수를 가져야 한다`() {
        val alerts = ObservabilityAssetsCatalog.alerts
        assertEquals("R260", alerts.round)
        assertEquals("alerts", alerts.kind)
        assertEquals("docs/alertmanager-rules.yaml", alerts.path)
        assertTrue(alerts.description.contains("14개")) { "14개 alerts 언급" }
        assertTrue(alerts.importInstructions.contains("prometheus.yml")) {
            "import 지침에 prometheus.yml 언급"
        }
    }

    @Test
    fun `all 리스트는 R256 R259 R260 순서로 정렬되어야 한다`() {
        val rounds = ObservabilityAssetsCatalog.all.map { it.round }
        assertEquals(listOf("R256", "R259", "R260"), rounds) {
            "도입 라운드 시간 순"
        }
    }

    @Test
    fun `Asset data class는 모든 필드가 비어있지 않아야 한다`() {
        for (asset in ObservabilityAssetsCatalog.all) {
            assertTrue(asset.round.isNotBlank()) { "${asset.round}: round 필드 비어있지 않음" }
            assertTrue(asset.kind.isNotBlank()) { "${asset.round}: kind 필드 비어있지 않음" }
            assertTrue(asset.path.isNotBlank()) { "${asset.round}: path 필드 비어있지 않음" }
            assertTrue(asset.description.isNotBlank()) {
                "${asset.round}: description 필드 비어있지 않음"
            }
            assertTrue(asset.importInstructions.isNotBlank()) {
                "${asset.round}: importInstructions 필드 비어있지 않음"
            }
        }
    }

    @Test
    fun `singleton object 참조는 안정적이어야 한다`() {
        // 객체가 싱글톤이므로 재참조해도 동일 인스턴스
        val first = ObservabilityAssetsCatalog
        val second = ObservabilityAssetsCatalog
        assertNotNull(first.all)
        assertEquals(first, second) { "object 싱글톤 동일성" }
        assertEquals(first.all.size, second.all.size)
    }
}
