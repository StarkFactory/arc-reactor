package com.arc.reactor.persona

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PersonaStore에 대한 테스트.
 *
 * 페르소나 저장소의 CRUD 동작을 검증합니다.
 */
class PersonaStoreTest {

    private lateinit var store: InMemoryPersonaStore

    @BeforeEach
    fun setup() {
        store = InMemoryPersonaStore()
    }

    @Nested
    inner class BasicCrud {

        @Test
        fun `have default persona on initialization해야 한다`() {
            val personas = store.list()

            assertEquals(1, personas.size) { "Should have 1 default persona" }
            assertEquals("default", personas[0].id) { "Default persona ID should be 'default'" }
            assertEquals("Default Assistant", personas[0].name) { "Default persona name should be 'Default Assistant'" }
            assertTrue(personas[0].isDefault) { "Default persona should be marked as default" }
        }

        @Test
        fun `save and retrieve a persona해야 한다`() {
            val persona = Persona(
                id = "test-1",
                name = "Test Persona",
                systemPrompt = "You are a test agent."
            )

            store.save(persona)
            val retrieved = store.get("test-1")

            assertNotNull(retrieved) { "Saved persona should be retrievable" }
            assertEquals("Test Persona", retrieved!!.name) { "Name should match" }
            assertEquals("You are a test agent.", retrieved.systemPrompt) { "System prompt should match" }
        }

        @Test
        fun `list all personas sorted by createdAt해야 한다`() {
            store.save(Persona(id = "p-2", name = "Second", systemPrompt = "prompt-2"))
            store.save(Persona(id = "p-3", name = "Third", systemPrompt = "prompt-3"))

            val personas = store.list()

            assertEquals(3, personas.size) { "Should have default + 2 saved personas" }
            assertEquals("default", personas[0].id) { "Default should be first (oldest)" }
        }

        @Test
        fun `delete a persona해야 한다`() {
            store.save(Persona(id = "to-delete", name = "Delete Me", systemPrompt = "prompt"))

            store.delete("to-delete")

            assertNull(store.get("to-delete")) { "Deleted persona should not be retrievable" }
        }

        @Test
        fun `nonexistent persona에 대해 return null해야 한다`() {
            assertNull(store.get("nonexistent")) { "Should return null for unknown ID" }
        }
    }

    @Nested
    inner class DefaultHandling {

        @Test
        fun `return default persona via getDefault해야 한다`() {
            val defaultPersona = store.getDefault()

            assertNotNull(defaultPersona) { "Should have a default persona" }
            assertTrue(defaultPersona!!.isDefault) { "getDefault() result should have isDefault=true" }
            assertEquals("default", defaultPersona.id) { "Default persona ID should be 'default'" }
        }

        @Test
        fun `saving new default일 때 clear existing default해야 한다`() {
            store.save(Persona(id = "new-default", name = "New Default", systemPrompt = "prompt", isDefault = true))

            val oldDefault = store.get("default")
            val newDefault = store.get("new-default")

            assertFalse(oldDefault!!.isDefault) { "Old default should be cleared" }
            assertTrue(newDefault!!.isDefault) { "New persona should be the default" }
        }

        @Test
        fun `updating isDefault to true일 때 clear existing default해야 한다`() {
            store.save(Persona(id = "candidate", name = "Candidate", systemPrompt = "prompt"))

            store.update("candidate", name = null, systemPrompt = null, isDefault = true)

            val oldDefault = store.get("default")
            val updated = store.get("candidate")

            assertFalse(oldDefault!!.isDefault) { "Old default should be cleared after update" }
            assertTrue(updated!!.isDefault) { "Updated persona should be the default" }
        }

        @Test
        fun `getDefault은(는) return null when no default exists해야 한다`() {
            // the seed default 삭제
            store.delete("default")

            assertNull(store.getDefault()) { "Should return null when no default persona exists" }
        }
    }

    @Nested
    inner class UpdateBehavior {

        @Test
        fun `partially update only provided fields해야 한다`() {
            store.save(Persona(id = "partial", name = "Original", systemPrompt = "Original Prompt"))

            val updated = store.update("partial", name = "Updated Name", systemPrompt = null, isDefault = null)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Updated Name", updated!!.name) { "Name should be updated" }
            assertEquals("Original Prompt", updated.systemPrompt) { "System prompt should remain unchanged" }
        }

        @Test
        fun `updating nonexistent persona일 때 return null해야 한다`() {
            val result = store.update("nonexistent", name = "New Name", systemPrompt = null, isDefault = null)

            assertNull(result) { "Update of nonexistent persona should return null" }
        }

        @Test
        fun `delete은(는) be idempotent for nonexistent persona해야 한다`() {
            assertDoesNotThrow { store.delete("nonexistent") }
        }
    }

    @Nested
    inner class ExtendedFields {

        @Test
        fun `save and retrieve all extended fields해야 한다`() {
            val persona = Persona(
                id = "ext-1",
                name = "Expert",
                systemPrompt = "You are an expert.",
                description = "A domain expert persona",
                responseGuideline = "Always respond in bullet points.",
                welcomeMessage = "Hello! How can I help?",
                icon = "🧑‍💻",
                isActive = true
            )

            store.save(persona)
            val retrieved = store.get("ext-1")

            assertNotNull(retrieved) { "Persona with extended fields should be retrievable" }
            assertEquals("A domain expert persona", retrieved!!.description) { "Description should match" }
            assertEquals("Always respond in bullet points.", retrieved.responseGuideline) { "Response guideline should match" }
            assertEquals("Hello! How can I help?", retrieved.welcomeMessage) { "Welcome message should match" }
            assertEquals("🧑‍💻", retrieved.icon) { "Icon should match" }
            assertTrue(retrieved.isActive) { "isActive should be true" }
        }

        @Test
        fun `default extended fields to null and isActive to true해야 한다`() {
            val persona = Persona(id = "minimal", name = "Minimal", systemPrompt = "prompt")

            store.save(persona)
            val retrieved = store.get("minimal")

            assertNotNull(retrieved) { "Minimal persona should be retrievable" }
            assertNull(retrieved!!.description) { "Description should default to null" }
            assertNull(retrieved.responseGuideline) { "Response guideline should default to null" }
            assertNull(retrieved.welcomeMessage) { "Welcome message should default to null" }
            assertNull(retrieved.icon) { "Icon should default to null" }
            assertTrue(retrieved.isActive) { "isActive should default to true" }
        }

        @Test
        fun `update extended fields independently해야 한다`() {
            store.save(Persona(
                id = "upd-ext",
                name = "Original",
                systemPrompt = "prompt",
                description = "Old desc",
                responseGuideline = "Old guideline"
            ))

            val updated = store.update(
                "upd-ext",
                description = "New desc",
                responseGuideline = "New guideline",
                icon = "🎯"
            )

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("New desc", updated!!.description) { "Description should be updated" }
            assertEquals("New guideline", updated.responseGuideline) { "Guideline should be updated" }
            assertEquals("🎯", updated.icon) { "Icon should be updated" }
            assertEquals("Original", updated.name) { "Name should remain unchanged" }
            assertEquals("prompt", updated.systemPrompt) { "System prompt should remain unchanged" }
        }

        @Test
        fun `update linked prompt template independently해야 한다`() {
            store.save(Persona(
                id = "templated",
                name = "Linked",
                systemPrompt = "fallback",
                promptTemplateId = "template-a"
            ))

            val updated = store.update("templated", promptTemplateId = "template-b")

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("template-b", updated!!.promptTemplateId) { "Linked prompt template should be updated" }
            assertEquals("fallback", updated.systemPrompt) { "Fallback system prompt should remain unchanged" }
        }

        @Test
        fun `update sends null일 때 preserve existing extended fields해야 한다`() {
            store.save(Persona(
                id = "preserve",
                name = "Persona",
                systemPrompt = "prompt",
                description = "Keep this",
                icon = "🔥"
            ))

            val updated = store.update("preserve", name = "Renamed")

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Renamed", updated!!.name) { "Name should be updated" }
            assertEquals("Keep this", updated.description) { "Description should be preserved when null is passed" }
            assertEquals("🔥", updated.icon) { "Icon should be preserved when null is passed" }
        }

        @Test
        fun `set isActive to false해야 한다`() {
            store.save(Persona(id = "deactivate", name = "Active", systemPrompt = "prompt", isActive = true))

            val updated = store.update("deactivate", isActive = false)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertFalse(updated!!.isActive) { "isActive should be false after deactivation" }
        }

        @Test
        fun `empty string is sent일 때 clear nullable fields해야 한다`() {
            store.save(Persona(
                id = "clearable",
                name = "Persona",
                systemPrompt = "prompt",
                description = "Has description",
                promptTemplateId = "template-a",
                icon = "🔥",
                welcomeMessage = "Hello"
            ))

            val updated = store.update("clearable", description = "", icon = "", welcomeMessage = "", promptTemplateId = "")

            assertNotNull(updated) { "Update should return the updated persona" }
            assertNull(updated!!.description) { "Description should be cleared to null" }
            assertNull(updated.icon) { "Icon should be cleared to null" }
            assertNull(updated.welcomeMessage) { "Welcome message should be cleared to null" }
            assertNull(updated.promptTemplateId) { "Linked prompt template should be cleared to null" }
        }
    }

    @Nested
    inner class ConcurrentDefaultHandling {

        @Test
        fun `concurrent saves with isDefault은(는) result in exactly one default해야 한다`() {
            val threadCount = 10
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = AtomicInteger(0)

            try {
                val futures = (1..threadCount).map { i ->
                    executor.submit {
                        latch.await()
                        try {
                            store.save(
                                Persona(
                                    id = "concurrent-$i",
                                    name = "Persona $i",
                                    systemPrompt = "Prompt $i",
                                    isDefault = true
                                )
                            )
                        } catch (e: Exception) {
                            errors.incrementAndGet()
                        }
                    }
                }

                latch.countDown()
                futures.forEach { it.get() }

                val defaults = store.list().filter { it.isDefault }
                assertEquals(1, defaults.size) {
                    "Should have exactly 1 default persona after concurrent saves, " +
                        "but found ${defaults.size}: ${defaults.map { it.id }}"
                }
            } finally {
                executor.shutdown()
            }
        }

        @Test
        fun `R285 concurrent update and delete은(는) never resurrect deleted persona해야 한다`() {
            // R285 fix 검증: delete가 synchronized 블록 안에 있어야 update가 deleted persona를
            // resurrect하지 않는다. 이전 구현(unsynchronized delete)에서는 다음 race가 가능했다:
            // 1. T1: update(id, ...) → synchronized 진입 → existing 읽음 → modify 후 personas[id]=updated
            // 2. T2: delete(id) → unsynchronized → personas.remove(id)
            // 3. T2.remove가 T1.put 직전에 실행되면 T1이 deleted persona를 resurrect
            val iterations = 200
            val seedId = "race-target"
            var resurrections = 0

            for (i in 1..iterations) {
                store.save(Persona(id = seedId, name = "iter-$i", systemPrompt = "p"))

                val executor = Executors.newFixedThreadPool(2)
                val latch = CountDownLatch(1)
                try {
                    val updateFuture = executor.submit {
                        latch.await()
                        store.update(seedId, name = "updated-$i")
                    }
                    val deleteFuture = executor.submit {
                        latch.await()
                        store.delete(seedId)
                    }
                    latch.countDown()
                    updateFuture.get()
                    deleteFuture.get()
                } finally {
                    executor.shutdown()
                }

                // delete가 항상 update 이후 또는 이전에 직렬화되어 실행되어야 한다.
                // 직렬화 후 가능한 최종 상태:
                // (a) update → delete: persona 삭제됨 (get == null)
                // (b) delete → update: update가 null 반환, persona 없음 (get == null)
                // 어느 경우든 get은 null이어야 한다. resurrection이 있으면 get != null.
                if (store.get(seedId) != null) {
                    resurrections++
                }
            }

            assertEquals(0, resurrections) {
                "R285 fix: $iterations 회 동시 update/delete 후 deleted persona가 resurrect된 횟수는 " +
                    "0이어야 하지만 ${resurrections}회 발생함. delete가 synchronized 블록 안에서 " +
                    "실행되지 않으면 update가 deleted persona를 다시 살리는 race condition 발생."
            }
        }
    }
}
