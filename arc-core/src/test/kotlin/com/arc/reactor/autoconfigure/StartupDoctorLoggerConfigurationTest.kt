package com.arc.reactor.autoconfigure

import com.arc.reactor.diagnostics.DoctorDiagnostics
import com.arc.reactor.diagnostics.StartupDoctorLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * R243: [StartupDoctorLogger]의 opt-in 자동 구성 검증.
 *
 * `arc.reactor.diagnostics.startup-log.enabled=true`일 때만 빈이 등록되고,
 * 기본값(미설정) 및 `false` 설정에서는 등록되지 않아야 한다.
 */
class StartupDoctorLoggerConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(DoctorDiagnosticsConfiguration::class.java)
        )

    @Nested
    inner class DefaultOff {

        @Test
        fun `기본값에서는 StartupDoctorLogger가 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                assertFalse(context.containsBean("startupDoctorLogger")) {
                    "opt-in 프로퍼티가 없으면 StartupDoctorLogger 빈은 등록되지 않아야 한다"
                }
                // DoctorDiagnostics는 항상 등록
                assertTrue(context.containsBean("doctorDiagnostics")) {
                    "DoctorDiagnostics는 opt-in과 무관하게 항상 등록"
                }
            }
        }

        @Test
        fun `enabled=false로 명시해도 등록되지 않아야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.diagnostics.startup-log.enabled=false")
                .run { context ->
                    assertFalse(
                        context.getBeansOfType(StartupDoctorLogger::class.java).isNotEmpty()
                    ) {
                        "false 명시이면 등록 금지"
                    }
                }
        }
    }

    @Nested
    inner class OptInOn {

        @Test
        fun `enabled=true이면 StartupDoctorLogger가 등록되어야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.diagnostics.startup-log.enabled=true")
                .run { context ->
                    val bean = context.getBean(StartupDoctorLogger::class.java)
                    assertNotNull(bean) { "enabled=true → 빈 등록" }
                }
        }

        @Test
        fun `enabled=true일 때 DoctorDiagnostics도 함께 주입되어야 한다`() {
            contextRunner
                .withPropertyValues("arc.reactor.diagnostics.startup-log.enabled=true")
                .run { context ->
                    val logger = context.getBean(StartupDoctorLogger::class.java)
                    val doctor = context.getBean(DoctorDiagnostics::class.java)
                    assertNotNull(logger) { "StartupDoctorLogger 빈" }
                    assertNotNull(doctor) { "DoctorDiagnostics 빈도 함께 주입" }
                }
        }

        @Test
        fun `include-details와 warn-on-issues 속성이 바인딩되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.diagnostics.startup-log.enabled=true",
                    "arc.reactor.diagnostics.startup-log.include-details=false",
                    "arc.reactor.diagnostics.startup-log.warn-on-issues=false"
                )
                .run { context ->
                    val bean = context.getBean(StartupDoctorLogger::class.java)
                    assertNotNull(bean) {
                        "추가 속성과 함께 등록되어야 한다"
                    }
                    // 속성 값 자체는 private이므로 존재만 검증 (동작은 StartupDoctorLoggerTest가 다룸)
                }
        }

        @Test
        fun `사용자 커스텀 빈이 있으면 기본 빈은 양보해야 한다 (ConditionalOnMissingBean)`() {
            contextRunner
                .withPropertyValues("arc.reactor.diagnostics.startup-log.enabled=true")
                .withBean(StartupDoctorLogger::class.java, { customStartupLogger() })
                .run { context ->
                    val beans = context.getBeansOfType(StartupDoctorLogger::class.java)
                    assertTrue(beans.size == 1) {
                        "커스텀 빈이 있으면 하나만 존재해야 한다"
                    }
                    // 커스텀 빈이 우선되었는지 확인 (동일성 검사)
                    val bean = beans.values.first()
                    assertTrue(bean === customLoggerInstance) {
                        "커스텀 빈이 우선되어야 한다"
                    }
                }
        }

        private var customLoggerInstance: StartupDoctorLogger? = null

        private fun customStartupLogger(): StartupDoctorLogger {
            // 최소한의 DoctorDiagnostics mock — 실제로 호출되지 않음 (ApplicationContext 종료 전)
            val doctor = io.mockk.mockk<DoctorDiagnostics>(relaxed = true)
            val instance = StartupDoctorLogger(doctor)
            customLoggerInstance = instance
            return instance
        }
    }
}
