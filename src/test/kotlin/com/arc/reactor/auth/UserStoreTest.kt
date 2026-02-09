package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserStoreTest {

    private lateinit var store: InMemoryUserStore

    @BeforeEach
    fun setup() {
        store = InMemoryUserStore()
    }

    @Nested
    inner class BasicCrud {

        @Test
        fun `should save and findById`() {
            val user = User(
                id = "user-1",
                email = "tony@stark.com",
                name = "Tony Stark",
                passwordHash = "hashed-password"
            )

            store.save(user)
            val retrieved = store.findById("user-1")

            assertNotNull(retrieved) { "Saved user should be retrievable by ID" }
            assertEquals("user-1", retrieved!!.id) { "User ID should match" }
            assertEquals("tony@stark.com", retrieved.email) { "Email should match" }
            assertEquals("Tony Stark", retrieved.name) { "Name should match" }
            assertEquals("hashed-password", retrieved.passwordHash) { "Password hash should match" }
        }

        @Test
        fun `should save and findByEmail`() {
            val user = User(
                id = "user-2",
                email = "pepper@stark.com",
                name = "Pepper Potts",
                passwordHash = "hashed-pw"
            )

            store.save(user)
            val retrieved = store.findByEmail("pepper@stark.com")

            assertNotNull(retrieved) { "Saved user should be retrievable by email" }
            assertEquals("user-2", retrieved!!.id) { "User ID should match" }
            assertEquals("pepper@stark.com", retrieved.email) { "Email should match" }
            assertEquals("Pepper Potts", retrieved.name) { "Name should match" }
        }

        @Test
        fun `existsByEmail should return true for existing user`() {
            store.save(
                User(id = "u-1", email = "exists@test.com", name = "Exists", passwordHash = "hash")
            )

            assertTrue(store.existsByEmail("exists@test.com")) {
                "existsByEmail should return true for saved user"
            }
        }

        @Test
        fun `existsByEmail should return false for unknown email`() {
            assertFalse(store.existsByEmail("unknown@test.com")) {
                "existsByEmail should return false for unknown email"
            }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should throw IllegalArgumentException on duplicate email`() {
            store.save(
                User(id = "u-1", email = "dup@test.com", name = "First", passwordHash = "hash1")
            )

            val exception = assertThrows(IllegalArgumentException::class.java) {
                store.save(
                    User(id = "u-2", email = "dup@test.com", name = "Second", passwordHash = "hash2")
                )
            }

            assertTrue(exception.message!!.contains("dup@test.com")) {
                "Exception message should mention the duplicate email, got: ${exception.message}"
            }
        }

        @Test
        fun `findById should return null for unknown ID`() {
            val result = store.findById("nonexistent-id")

            assertNull(result) { "findById should return null for unknown ID" }
        }

        @Test
        fun `findByEmail should return null for unknown email`() {
            val result = store.findByEmail("nonexistent@test.com")

            assertNull(result) { "findByEmail should return null for unknown email" }
        }
    }

    @Nested
    inner class Count {

        @Test
        fun `count should return 0 when empty`() {
            assertEquals(0L, store.count()) { "Empty store should have count 0" }
        }

        @Test
        fun `count should return correct number after saves`() {
            store.save(User(id = "u-1", email = "a@test.com", name = "A", passwordHash = "h"))
            store.save(User(id = "u-2", email = "b@test.com", name = "B", passwordHash = "h"))

            assertEquals(2L, store.count()) { "Count should be 2 after saving 2 users" }
        }
    }

    @Nested
    inner class RoleField {

        @Test
        fun `should default to USER role`() {
            val user = User(id = "u-1", email = "a@test.com", name = "A", passwordHash = "h")

            assertEquals(UserRole.USER, user.role) { "Default role should be USER" }
        }

        @Test
        fun `should preserve ADMIN role`() {
            val user = User(id = "u-1", email = "a@test.com", name = "A", passwordHash = "h", role = UserRole.ADMIN)
            store.save(user)

            val retrieved = store.findById("u-1")

            assertNotNull(retrieved) { "User should be retrievable" }
            assertEquals(UserRole.ADMIN, retrieved!!.role) { "Role should be preserved as ADMIN" }
        }
    }

    @Nested
    inner class Concurrency {

        @Test
        fun `should save multiple users with different emails`() {
            val users = (1..10).map { i ->
                User(
                    id = "user-$i",
                    email = "user$i@test.com",
                    name = "User $i",
                    passwordHash = "hash-$i"
                )
            }

            users.forEach { store.save(it) }

            users.forEach { user ->
                val byId = store.findById(user.id)
                assertNotNull(byId) { "User ${user.id} should be retrievable by ID" }
                assertEquals(user.email, byId!!.email) { "Email should match for ${user.id}" }

                val byEmail = store.findByEmail(user.email)
                assertNotNull(byEmail) { "User with email ${user.email} should be retrievable" }
                assertEquals(user.id, byEmail!!.id) { "ID should match for ${user.email}" }
            }
        }
    }
}
