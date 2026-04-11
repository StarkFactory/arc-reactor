package com.arc.reactor.diagnostics

/**
 * R256/R259/R260 운영 자산 카탈로그.
 *
 * 평가 메트릭(`arc.reactor.evaluation.metrics.enabled=true`)을 활성화한 운영자가
 * 즉시 활용할 수 있는 3종 자산을 정적으로 카탈로그화한다. 각 자산은 R256~R260의
 * 도큐먼트 산출물(`docs/`) 또는 단일 파일 형태로 제공되며, 운영자가 cold-start
 * 워크플로우에서 사용할 import 지침을 함께 포함한다.
 *
 * ## 3종 세트
 *
 * | 라운드 | 종류 | 위치 | 사용 |
 * |--------|------|------|------|
 * | R256 | 운영 플레이북 | `docs/evaluation-metrics.md` | 알림 발생 시 stage 매뉴얼 |
 * | R259 | Grafana 대시보드 | 동일 파일 (JSON 섹션) | Grafana UI import |
 * | R260 | Alertmanager 규칙 | `docs/alertmanager-rules.yaml` | prometheus.yml include |
 *
 * ## 설계 원칙
 *
 * - **순수 정적 데이터** — 런타임 I/O 없음, classpath/filesystem 접근 없음
 * - **컴파일타임 안전** — 자산이 추가/수정되면 코드 변경 필요 (의도적 마찰)
 * - **DoctorDiagnostics 노출용** — `diagnoseObservabilityAssets()`가 이 카탈로그를 사용
 *
 * @see DoctorDiagnostics 운영 자산 섹션 진단 (R261)
 */
object ObservabilityAssetsCatalog {

    /**
     * 단일 운영 자산 항목.
     *
     * @property round 도입 라운드 (예: "R256")
     * @property kind 자산 종류 ("playbook" | "dashboard" | "alerts")
     * @property path 레포지토리 상대 경로 또는 문서 내 anchor
     * @property description 자산 내용 한 줄 요약
     * @property importInstructions 운영자 cold-start 워크플로우 지침
     */
    data class Asset(
        val round: String,
        val kind: String,
        val path: String,
        val description: String,
        val importInstructions: String
    )

    /** R256 운영 플레이북 — 9개 stage 대응 매뉴얼. */
    val playbook: Asset = Asset(
        round = "R256",
        kind = "playbook",
        path = "docs/evaluation-metrics.md (execution.error Stage 운영 플레이북 섹션)",
        description = "9개 ExecutionStage 운영 매뉴얼 + 대응 팀 라우팅 매트릭스",
        importInstructions = "알림 발생 시 stage 검색 → 대응 매뉴얼 단계대로 진행"
    )

    /** R259 Grafana 대시보드 JSON — 15개 패널. */
    val dashboard: Asset = Asset(
        round = "R259",
        kind = "dashboard",
        path = "docs/evaluation-metrics.md (Grafana 대시보드 템플릿 섹션)",
        description = "15개 패널 (task-level 9 + execution.error stage 6)",
        importInstructions = "JSON 복사 → Grafana UI → Dashboards → New → Import → Paste"
    )

    /** R260 Alertmanager 규칙 YAML — 14개 alerts. */
    val alerts: Asset = Asset(
        round = "R260",
        kind = "alerts",
        path = "docs/alertmanager-rules.yaml",
        description = "14개 alerts in 2 groups (task-level 5 + execution.error 9)",
        importInstructions = "prometheus.yml의 rule_files에 경로 등록 → Prometheus reload"
    )

    /** 모든 자산 (R256/R259/R260 순). */
    val all: List<Asset> = listOf(playbook, dashboard, alerts)
}
