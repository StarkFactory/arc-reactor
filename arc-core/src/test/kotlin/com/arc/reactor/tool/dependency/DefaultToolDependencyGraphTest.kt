package com.arc.reactor.tool.dependency

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [DefaultToolDependencyGraph] 단위 테스트.
 */
class DefaultToolDependencyGraphTest {

    @Nested
    inner class AddAndGetDependencies {

        @Test
        fun `의존성을 추가하면 조회할 수 있다`() {
            val graph = DefaultToolDependencyGraph()

            graph.addDependency("toolB", "toolA")
            val deps = graph.getDependencies("toolB")

            deps shouldBe setOf("toolA")
        }

        @Test
        fun `ToolDependency 객체로 여러 의존성을 한 번에 추가할 수 있다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency(
                ToolDependency(
                    toolName = "toolC",
                    dependsOn = setOf("toolA", "toolB"),
                    description = "toolA, toolB 출력 필요"
                )
            )

            val deps = graph.getDependencies("toolC")

            deps.shouldContainExactlyInAnyOrder("toolA", "toolB")
        }

        @Test
        fun `등록되지 않은 도구의 의존성은 빈 집합을 반환한다`() {
            val graph = DefaultToolDependencyGraph()

            val deps = graph.getDependencies("unknown")

            deps.shouldBeEmpty()
        }

        @Test
        fun `같은 도구에 의존성을 여러 번 추가하면 누적된다`() {
            val graph = DefaultToolDependencyGraph()

            graph.addDependency("toolC", "toolA")
            graph.addDependency("toolC", "toolB")
            val deps = graph.getDependencies("toolC")

            deps.shouldContainExactlyInAnyOrder("toolA", "toolB")
        }
    }

    @Nested
    inner class ExecutionPlanTest {

        @Test
        fun `독립 도구는 모두 같은 계층(병렬)에 배치된다`() {
            val graph = DefaultToolDependencyGraph()
            val plan = graph.getExecutionPlan(
                setOf("toolA", "toolB", "toolC")
            )

            plan.totalTools shouldBe 3
            plan.layers shouldHaveSize 1
            plan.layers[0].toolNames.shouldContainExactlyInAnyOrder(
                "toolA", "toolB", "toolC"
            )
            plan.layers[0].isParallel shouldBe true
        }

        @Test
        fun `의존 도구는 후속 계층에 배치된다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolB", "toolA")

            val plan = graph.getExecutionPlan(
                setOf("toolA", "toolB")
            )

            plan.layers shouldHaveSize 2
            plan.layers[0].toolNames shouldBe setOf("toolA")
            plan.layers[0].isParallel shouldBe false
            plan.layers[1].toolNames shouldBe setOf("toolB")
        }

        @Test
        fun `단일 체인 A-B-C는 3개 순차 계층을 생성한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolB", "toolA")
            graph.addDependency("toolC", "toolB")

            val plan = graph.getExecutionPlan(
                setOf("toolA", "toolB", "toolC")
            )

            plan.layers shouldHaveSize 3
            plan.layers[0].toolNames shouldBe setOf("toolA")
            plan.layers[1].toolNames shouldBe setOf("toolB")
            plan.layers[2].toolNames shouldBe setOf("toolC")
        }

        @Test
        fun `다이아몬드 패턴 A-B,C-D는 3개 계층을 생성한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolB", "toolA")
            graph.addDependency("toolC", "toolA")
            graph.addDependency(
                ToolDependency(
                    toolName = "toolD",
                    dependsOn = setOf("toolB", "toolC")
                )
            )

            val plan = graph.getExecutionPlan(
                setOf("toolA", "toolB", "toolC", "toolD")
            )

            plan.layers shouldHaveSize 3
            plan.layers[0].toolNames shouldBe setOf("toolA")
            plan.layers[1].toolNames
                .shouldContainExactlyInAnyOrder("toolB", "toolC")
            plan.layers[1].isParallel shouldBe true
            plan.layers[2].toolNames shouldBe setOf("toolD")
        }

        @Test
        fun `빈 도구 집합은 빈 실행 계획을 반환한다`() {
            val graph = DefaultToolDependencyGraph()

            val plan = graph.getExecutionPlan(emptySet())

            plan.totalTools shouldBe 0
            plan.layers.shouldBeEmpty()
        }

        @Test
        fun `요청 범위 밖의 의존성은 무시한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolB", "toolA")
            graph.addDependency("toolC", "toolB")

            // toolA만 요청 — toolB 의존성은 무시됨
            val plan = graph.getExecutionPlan(setOf("toolB"))

            plan.layers shouldHaveSize 1
            plan.layers[0].toolNames shouldBe setOf("toolB")
        }

        @Test
        fun `복잡한 DAG에서 올바른 계층 순서를 생성한다`() {
            val graph = DefaultToolDependencyGraph()
            // E → C, D
            // D → B
            // C → A
            // B → A
            graph.addDependency("toolB", "toolA")
            graph.addDependency("toolC", "toolA")
            graph.addDependency("toolD", "toolB")
            graph.addDependency(
                ToolDependency(
                    toolName = "toolE",
                    dependsOn = setOf("toolC", "toolD")
                )
            )

            val plan = graph.getExecutionPlan(
                setOf("toolA", "toolB", "toolC", "toolD", "toolE")
            )

            plan.layers shouldHaveSize 4
            plan.layers[0].toolNames shouldBe setOf("toolA")
            plan.layers[1].toolNames
                .shouldContainExactlyInAnyOrder("toolB", "toolC")
            plan.layers[2].toolNames shouldBe setOf("toolD")
            plan.layers[3].toolNames shouldBe setOf("toolE")
        }

        @Test
        fun `단일 도구만 요청하면 1개 계층을 생성한다`() {
            val graph = DefaultToolDependencyGraph()

            val plan = graph.getExecutionPlan(setOf("toolA"))

            plan.layers shouldHaveSize 1
            plan.layers[0].toolNames shouldBe setOf("toolA")
            plan.layers[0].isParallel shouldBe false
        }
    }

    @Nested
    inner class CycleDetection {

        @Test
        fun `A-B-C-A 순환 의존성을 감지한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolA", "toolC")
            graph.addDependency("toolB", "toolA")
            graph.addDependency("toolC", "toolB")

            val ex = assertThrows<IllegalStateException> {
                graph.getExecutionPlan(
                    setOf("toolA", "toolB", "toolC")
                )
            }
            ex.message!!.contains("순환") shouldBe true
        }

        @Test
        fun `자기 참조 의존성을 감지한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolA", "toolA")

            val ex = assertThrows<IllegalStateException> {
                graph.getExecutionPlan(setOf("toolA"))
            }
            ex.message!!.contains("순환") shouldBe true
        }

        @Test
        fun `A-B 상호 의존성을 감지한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolA", "toolB")
            graph.addDependency("toolB", "toolA")

            val ex = assertThrows<IllegalStateException> {
                graph.getExecutionPlan(
                    setOf("toolA", "toolB")
                )
            }
            ex.message!!.contains("순환") shouldBe true
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `유효한 DAG는 빈 오류 목록을 반환한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolB", "toolA")
            graph.addDependency("toolC", "toolA")

            val errors = graph.validate()

            errors.shouldBeEmpty()
        }

        @Test
        fun `자기 참조 의존성을 검출한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolA", "toolA")

            val errors = graph.validate()

            errors.any {
                it.contains("자기 참조")
            } shouldBe true
        }

        @Test
        fun `순환 의존성을 검출한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolA", "toolB")
            graph.addDependency("toolB", "toolC")
            graph.addDependency("toolC", "toolA")

            val errors = graph.validate()

            // 자기 참조는 없으므로 순환만 검출
            errors.any {
                it.contains("순환")
            } shouldBe true
        }

        @Test
        fun `빈 그래프는 유효하다`() {
            val graph = DefaultToolDependencyGraph()

            val errors = graph.validate()

            errors.shouldBeEmpty()
        }

        @Test
        fun `자기 참조와 순환이 동시에 있으면 모두 검출한다`() {
            val graph = DefaultToolDependencyGraph()
            graph.addDependency("toolA", "toolA")
            graph.addDependency("toolB", "toolC")
            graph.addDependency("toolC", "toolB")

            val errors = graph.validate()

            errors.any {
                it.contains("자기 참조")
            } shouldBe true
            errors.any {
                it.contains("순환")
            } shouldBe true
        }
    }

    @Nested
    inner class ExecutionLayerProperties {

        @Test
        fun `도구 1개 계층은 isParallel이 false이다`() {
            val layer = ToolExecutionLayer(
                index = 0,
                toolNames = setOf("toolA")
            )

            layer.isParallel shouldBe false
        }

        @Test
        fun `도구 2개 이상 계층은 isParallel이 true이다`() {
            val layer = ToolExecutionLayer(
                index = 0,
                toolNames = setOf("toolA", "toolB")
            )

            layer.isParallel shouldBe true
        }
    }
}
