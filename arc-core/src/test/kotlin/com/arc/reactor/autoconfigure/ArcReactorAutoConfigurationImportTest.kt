package com.arc.reactor.autoconfigure

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

class ArcReactorAutoConfigurationImportTest {

    @Test
    fun `auto configuration should import modular configuration classes`() {
        val import = ArcReactorAutoConfiguration::class.java.getAnnotation(Import::class.java)
        val imported = import.value.toSet()

        assertTrue(imported.contains(ArcReactorCoreBeansConfiguration::class))
        assertTrue(imported.contains(ArcReactorHookAndMcpConfiguration::class))
        assertTrue(imported.contains(ArcReactorRuntimeConfiguration::class))
        assertTrue(imported.contains(ArcReactorExecutorConfiguration::class))
    }
}
