package com.arc.reactor.integration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.writeText

@Tag("integration")
class FlywayUpgradePathIntegrationTest {

    @Test
    fun `should fail upgrade when an applied migration file is modified`() {
        val migrationsDir = Files.createTempDirectory("flyway-upgrade-immutability")
        try {
            val v1 = migrationsDir.resolve("V1__create_sample.sql")
            v1.writeText("CREATE TABLE sample (id INT PRIMARY KEY);")

            val dataSource = newH2DataSource()
            newFlyway(dataSource, migrationsDir).migrate()

            // Simulate illegal history rewrite: edited V1 after it was already applied.
            v1.writeText("CREATE TABLE sample (id BIGINT PRIMARY KEY);")

            val error = assertThrows(
                FlywayValidateException::class.java,
                { newFlyway(dataSource, migrationsDir).migrate() },
                "Modified applied migration must fail with FlywayValidateException"
            )
            assertTrue(
                error.message?.contains("checksum", ignoreCase = true) == true,
                "Validation message should mention checksum mismatch"
            )
        } finally {
            deleteDirectoryRecursively(migrationsDir)
        }
    }

    @Test
    fun `should allow additive upgrade with a new migration version`() {
        val migrationsDir = Files.createTempDirectory("flyway-upgrade-additive")
        try {
            val v1 = migrationsDir.resolve("V1__create_sample.sql")
            v1.writeText("CREATE TABLE sample (id INT PRIMARY KEY);")

            val dataSource = newH2DataSource()
            val first = newFlyway(dataSource, migrationsDir).migrate()
            assertEquals(
                1,
                first.migrationsExecuted,
                "Initial migration should execute exactly one version"
            )

            val v2 = migrationsDir.resolve("V2__add_sample_name.sql")
            v2.writeText("ALTER TABLE sample ADD COLUMN name VARCHAR(100);")

            val second = newFlyway(dataSource, migrationsDir).migrate()
            assertEquals(
                1,
                second.migrationsExecuted,
                "Additive upgrade should execute only the newly added migration"
            )
        } finally {
            deleteDirectoryRecursively(migrationsDir)
        }
    }

    private fun newH2DataSource(): DriverManagerDataSource {
        val dbName = "flyway_upgrade_${UUID.randomUUID()}".replace("-", "")
        val url = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        return DriverManagerDataSource(url, "sa", "")
    }

    private fun newFlyway(dataSource: DriverManagerDataSource, migrationsDir: Path): Flyway {
        return Flyway.configure()
            .validateMigrationNaming(true)
            .dataSource(dataSource)
            .locations("filesystem:${migrationsDir.toAbsolutePath()}")
            .load()
    }

    private fun deleteDirectoryRecursively(path: Path) {
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
