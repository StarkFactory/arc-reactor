package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.time.Instant

class JdbcUserStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcUserStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // V3 + V6 combined: users table with role column
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id            VARCHAR(36)   PRIMARY KEY,
                email         VARCHAR(255)  NOT NULL UNIQUE,
                name          VARCHAR(100)  NOT NULL,
                password_hash VARCHAR(255)  NOT NULL,
                role          VARCHAR(20)   NOT NULL DEFAULT 'USER',
                created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        store = JdbcUserStore(jdbcTemplate)
    }

    private fun createUser(
        id: String = "user-1",
        email: String = "test@example.com",
        name: String = "Test User",
        passwordHash: String = "hashed-pw",
        role: UserRole = UserRole.USER
    ) = User(id = id, email = email, name = name, passwordHash = passwordHash, role = role)

    @Nested
    inner class BasicCrud {

        @Test
        fun `should save and find by id`() {
            val user = createUser()
            store.save(user)

            val found = store.findById("user-1")

            assertNotNull(found) { "Saved user should be retrievable by ID" }
            assertEquals("user-1", found!!.id) { "User ID should match" }
            assertEquals("test@example.com", found.email) { "Email should match" }
            assertEquals("Test User", found.name) { "Name should match" }
            assertEquals("hashed-pw", found.passwordHash) { "Password hash should match" }
        }

        @Test
        fun `should save and find by email`() {
            store.save(createUser())

            val found = store.findByEmail("test@example.com")

            assertNotNull(found) { "Saved user should be retrievable by email" }
            assertEquals("user-1", found!!.id) { "User ID should match" }
        }

        @Test
        fun `should update name and role`() {
            val user = createUser()
            store.save(user)

            val updated = user.copy(name = "Updated Name", role = UserRole.ADMIN)
            store.update(updated)

            val found = store.findById("user-1")
            assertNotNull(found) { "Updated user should be retrievable" }
            assertEquals("Updated Name", found!!.name) { "Name should be updated" }
            assertEquals(UserRole.ADMIN, found.role) { "Role should be updated to ADMIN" }
        }

        @Test
        fun `should check existsByEmail`() {
            assertFalse(store.existsByEmail("test@example.com")) { "Should not exist before save" }

            store.save(createUser())

            assertTrue(store.existsByEmail("test@example.com")) { "Should exist after save" }
            assertFalse(store.existsByEmail("other@example.com")) { "Other email should not exist" }
        }

        @Test
        fun `should count users`() {
            assertEquals(0, store.count()) { "Count should be 0 initially" }

            store.save(createUser(id = "u1", email = "a@test.com"))
            store.save(createUser(id = "u2", email = "b@test.com"))

            assertEquals(2, store.count()) { "Count should be 2 after saving 2 users" }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should return null for unknown id`() {
            val found = store.findById("nonexistent")

            assertNull(found, "Should return null for unknown ID")
        }

        @Test
        fun `should return null for unknown email`() {
            val found = store.findByEmail("nobody@test.com")

            assertNull(found, "Should return null for unknown email")
        }

        @Test
        fun `should throw on duplicate email`() {
            store.save(createUser(id = "u1", email = "dup@test.com"))

            assertThrows(Exception::class.java, {
                store.save(createUser(id = "u2", email = "dup@test.com"))
            }) { "Saving duplicate email should throw" }
        }

        @Test
        fun `should roundtrip USER role`() {
            store.save(createUser(role = UserRole.USER))

            val found = store.findById("user-1")
            assertEquals(UserRole.USER, found!!.role) { "USER role should roundtrip correctly" }
        }

        @Test
        fun `should roundtrip ADMIN role`() {
            store.save(createUser(role = UserRole.ADMIN))

            val found = store.findById("user-1")
            assertEquals(UserRole.ADMIN, found!!.role) { "ADMIN role should roundtrip correctly" }
        }

        @Test
        fun `should preserve created_at timestamp`() {
            val createdAt = Instant.parse("2026-01-15T10:00:00Z")
            store.save(createUser().copy(createdAt = createdAt))

            val found = store.findById("user-1")
            assertNotNull(found) { "User should be retrievable" }
            assertEquals(
                createdAt.epochSecond,
                found!!.createdAt.epochSecond
            ) { "createdAt should be preserved (second precision)" }
        }
    }
}
