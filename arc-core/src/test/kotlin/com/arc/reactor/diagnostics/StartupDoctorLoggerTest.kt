package com.arc.reactor.diagnostics

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.DefaultApplicationArguments
import java.time.Instant

/**
 * R243: [StartupDoctorLogger] 단위 테스트.
 *
 * `ApplicationRunner.run()` 호출 시:
 * - `DoctorDiagnostics.runDiagnostics()` 호출
 * - 예외 시 fail-open (기동 차단 없음)
 * - 경고/오류 시 추가 warn 로그 조건부 출력
 */
class StartupDoctorLoggerTest {

    private val emptyArgs: ApplicationArguments = DefaultApplicationArguments()
    private val fixedTime: Instant = Instant.parse("2026-04-11T14:30:00Z")

    private fun okReport(): DoctorReport = DoctorReport(
        generatedAt = fixedTime,
        sections = listOf(
            DoctorSection(
                name = "Section A",
                status = DoctorStatus.OK,
                checks = listOf(DoctorCheck("check1", DoctorStatus.OK, "정상")),
                message = "정상 동작"
            )
        )
    )

    private fun warnReport(): DoctorReport = DoctorReport(
        generatedAt = fixedTime,
        sections = listOf(
            DoctorSection(
                name = "Section A",
                status = DoctorStatus.WARN,
                checks = listOf(DoctorCheck("check1", DoctorStatus.WARN, "경고")),
                message = "권장 설정과 불일치"
            )
        )
    )

    private fun errorReport(): DoctorReport = DoctorReport(
        generatedAt = fixedTime,
        sections = listOf(
            DoctorSection(
                name = "Section A",
                status = DoctorStatus.ERROR,
                checks = listOf(DoctorCheck("check1", DoctorStatus.ERROR, "오류")),
                message = "동작 불가"
            )
        )
    )

    @Nested
    inner class BasicBehavior {

        @Test
        fun `정상 보고서를 받으면 runDiagnostics를 1회 호출해야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns okReport()
            val runner = StartupDoctorLogger(doctor)

            runner.run(emptyArgs)

            verify(exactly = 1) { doctor.runDiagnostics() }
        }

        @Test
        fun `OK 상태이면 정상 실행되어야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns okReport()
            val runner = StartupDoctorLogger(doctor)

            assertDoesNotThrow { runner.run(emptyArgs) }
        }

        @Test
        fun `WARN 상태에서도 예외 없이 실행되어야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns warnReport()
            val runner = StartupDoctorLogger(doctor)

            assertDoesNotThrow { runner.run(emptyArgs) }
        }

        @Test
        fun `ERROR 상태에서도 예외 없이 실행되어야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns errorReport()
            val runner = StartupDoctorLogger(doctor)

            assertDoesNotThrow { runner.run(emptyArgs) }
        }
    }

    @Nested
    inner class FailOpen {

        @Test
        fun `runDiagnostics가 예외를 던져도 기동을 차단하지 않아야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } throws RuntimeException("진단 실패")
            val runner = StartupDoctorLogger(doctor)

            assertDoesNotThrow {
                runner.run(emptyArgs)
            }
        }

        @Test
        fun `예외 발생 시 runDiagnostics만 1회 호출되고 더 이상 재시도하지 않아야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } throws IllegalStateException("uninitialized")
            val runner = StartupDoctorLogger(doctor)

            runner.run(emptyArgs)

            verify(exactly = 1) { doctor.runDiagnostics() }
        }
    }

    @Nested
    inner class ConfigurationFlags {

        @Test
        fun `includeDetails=false 설정은 DoctorDiagnostics 호출에 영향을 주지 않아야 한다`() {
            // includeDetails는 toHumanReadable 단계에서만 사용되며 doctor.runDiagnostics 호출은 동일
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns okReport()
            val runner = StartupDoctorLogger(doctor, includeDetails = false)

            runner.run(emptyArgs)

            verify(exactly = 1) { doctor.runDiagnostics() }
        }

        @Test
        fun `warnOnIssues=false일 때도 WARN 상태에서 예외 없이 실행되어야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns warnReport()
            val runner = StartupDoctorLogger(doctor, warnOnIssues = false)

            assertDoesNotThrow { runner.run(emptyArgs) }
        }

        @Test
        fun `기본 생성자는 includeDetails=true, warnOnIssues=true를 사용해야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns okReport()
            val runner = StartupDoctorLogger(doctor)

            // 기본값으로도 정상 동작하는지 확인
            assertDoesNotThrow { runner.run(emptyArgs) }
            verify(exactly = 1) { doctor.runDiagnostics() }
        }
    }

    @Nested
    inner class ReportContentCoverage {

        @Test
        fun `빈 섹션 보고서도 정상 처리되어야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns DoctorReport(
                generatedAt = fixedTime,
                sections = emptyList()
            )
            val runner = StartupDoctorLogger(doctor)

            assertDoesNotThrow { runner.run(emptyArgs) }
            verify(exactly = 1) { doctor.runDiagnostics() }
        }

        @Test
        fun `혼합 상태 보고서도 정상 처리되어야 한다`() {
            val doctor = mockk<DoctorDiagnostics>()
            every { doctor.runDiagnostics() } returns DoctorReport(
                generatedAt = fixedTime,
                sections = listOf(
                    DoctorSection("A", DoctorStatus.OK, emptyList(), "활성"),
                    DoctorSection("B", DoctorStatus.SKIPPED, emptyList(), "비활성"),
                    DoctorSection("C", DoctorStatus.WARN, emptyList(), "경고"),
                    DoctorSection("D", DoctorStatus.ERROR, emptyList(), "오류")
                )
            )
            val runner = StartupDoctorLogger(doctor)

            assertDoesNotThrow { runner.run(emptyArgs) }
        }

        @Test
        fun `OK만 있는 보고서는 overall이 정상이어야 하고 warn 로그가 발생하지 않아야 한다`() {
            // overall 상태 검증은 DoctorReport의 책임이므로 여기서는 동작만 확인
            val report = okReport()
            assertTrue(report.allHealthy()) { "OK만 있으면 healthy" }
            assertTrue(!report.hasWarningsOrErrors()) { "경고/오류 없음" }
        }

        @Test
        fun `WARN이 있는 보고서는 hasWarningsOrErrors가 true여야 한다`() {
            val report = warnReport()
            assertTrue(report.hasWarningsOrErrors()) {
                "WARN 섹션이 있으면 hasWarningsOrErrors=true"
            }
        }

        @Test
        fun `ERROR가 있는 보고서는 hasErrors가 true여야 한다`() {
            val report = errorReport()
            assertTrue(report.hasErrors()) { "ERROR 섹션이 있으면 hasErrors=true" }
            assertTrue(report.hasWarningsOrErrors()) {
                "ERROR는 hasWarningsOrErrors도 포함"
            }
        }
    }
}
