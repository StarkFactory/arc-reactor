package com.arc.reactor.memory

import com.arc.reactor.memory.impl.InMemoryUserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [UserMemoryManager]의 위임 메서드 테스트.
 *
 * get/save/delete/updateFact/updatePreference 메서드가
 * 기저 [UserMemoryStore]에 올바르게 위임되는지 검증한다.
 */
class UserMemoryManagerDelegationTest {

    private val store = InMemoryUserMemoryStore()
    private val manager = UserMemoryManager(store = store)

    @Nested
    inner class GetDelegation {

        @Test
        fun `없는 사용자에 대해 null을 반환한다`() = runTest {
            val result = manager.get("nonexistent-user")

            assertNull(result, "존재하지 않는 사용자에 대해 get은 null을 반환해야 한다")
        }

        @Test
        fun `저장된 사용자 기억을 반환한다`() = runTest {
            val memory = UserMemory(userId = "u1", facts = mapOf("role" to "engineer"))
            store.save("u1", memory)

            val result = manager.get("u1")

            assertNotNull(result, "저장된 메모리가 get으로 반환되어야 한다")
            assertEquals("engineer", result!!.facts["role"], "facts가 정확히 반환되어야 한다")
        }
    }

    @Nested
    inner class SaveDelegation {

        @Test
        fun `저장 후 기억을 조회할 수 있다`() = runTest {
            val memory = UserMemory(
                userId = "save-user",
                facts = mapOf("team" to "platform"),
                preferences = mapOf("language" to "Korean")
            )

            manager.save("save-user", memory)

            val result = store.get("save-user")
            assertNotNull(result, "manager.save 후 store에서 조회 가능해야 한다")
            assertEquals("platform", result!!.facts["team"], "저장된 facts가 일치해야 한다")
            assertEquals("Korean", result.preferences["language"], "저장된 preferences가 일치해야 한다")
        }

        @Test
        fun `두 번 save하면 기존 기억을 덮어쓴다`() = runTest {
            manager.save("overwrite-user", UserMemory(userId = "overwrite-user", facts = mapOf("old" to "data")))
            manager.save("overwrite-user", UserMemory(userId = "overwrite-user", facts = mapOf("new" to "data")))

            val result = manager.get("overwrite-user")
            assertNotNull(result, "두 번째 save 후 기억이 존재해야 한다")
            assertTrue(result!!.facts.containsKey("new"), "두 번째 save의 facts가 반영되어야 한다")
            assertTrue(!result.facts.containsKey("old"), "첫 번째 save의 facts는 덮어써져야 한다")
        }
    }

    @Nested
    inner class DeleteDelegation {

        @Test
        fun `delete 후 기억이 사라진다`() = runTest {
            manager.save("del-user", UserMemory(userId = "del-user"))

            manager.delete("del-user")

            assertNull(manager.get("del-user"), "delete 후 get은 null을 반환해야 한다")
        }

        @Test
        fun `존재하지 않는 사용자를 delete해도 예외가 발생하지 않는다`() = runTest {
            // 예외 없이 완료되어야 한다
            manager.delete("never-existed")

            assertNull(manager.get("never-existed"), "존재하지 않는 사용자 delete 후 get은 null이어야 한다")
        }
    }

    @Nested
    inner class UpdateFactDelegation {

        @Test
        fun `updateFact이 새 팩트를 추가한다`() = runTest {
            manager.save("fact-user", UserMemory(userId = "fact-user", facts = mapOf("team" to "backend")))

            manager.updateFact("fact-user", "role", "lead")

            val result = manager.get("fact-user")
            assertNotNull(result, "updateFact 후 기억이 존재해야 한다")
            assertEquals("backend", result!!.facts["team"], "기존 팩트 'team'이 유지되어야 한다")
            assertEquals("lead", result.facts["role"], "새 팩트 'role'이 추가되어야 한다")
        }

        @Test
        fun `updateFact이 기존 팩트를 덮어쓴다`() = runTest {
            manager.save("overwrite-fact-user", UserMemory(userId = "overwrite-fact-user", facts = mapOf("team" to "backend")))

            manager.updateFact("overwrite-fact-user", "team", "platform")

            val result = manager.get("overwrite-fact-user")
            assertNotNull(result, "updateFact 후 기억이 존재해야 한다")
            assertEquals("platform", result!!.facts["team"], "팩트 'team'이 'platform'으로 갱신되어야 한다")
            assertEquals(1, result.facts.size, "팩트 맵 크기는 증가하지 않아야 한다")
        }

        @Test
        fun `기억이 없는 사용자에게 updateFact을 호출하면 새 기억이 생성된다`() = runTest {
            manager.updateFact("brand-new-user", "lang", "Kotlin")

            val result = manager.get("brand-new-user")
            assertNotNull(result, "updateFact이 신규 사용자의 기억을 생성해야 한다")
            assertEquals("Kotlin", result!!.facts["lang"], "신규 사용자의 팩트가 저장되어야 한다")
        }
    }

    @Nested
    inner class UpdatePreferenceDelegation {

        @Test
        fun `updatePreference이 새 선호도를 추가한다`() = runTest {
            manager.save("pref-user", UserMemory(userId = "pref-user", preferences = mapOf("language" to "Korean")))

            manager.updatePreference("pref-user", "detail_level", "brief")

            val result = manager.get("pref-user")
            assertNotNull(result, "updatePreference 후 기억이 존재해야 한다")
            assertEquals("Korean", result!!.preferences["language"], "기존 선호도 'language'가 유지되어야 한다")
            assertEquals("brief", result.preferences["detail_level"], "새 선호도 'detail_level'이 추가되어야 한다")
        }

        @Test
        fun `updatePreference이 기존 선호도를 덮어쓴다`() = runTest {
            manager.save("pref-overwrite", UserMemory(userId = "pref-overwrite", preferences = mapOf("language" to "Korean")))

            manager.updatePreference("pref-overwrite", "language", "English")

            val result = manager.get("pref-overwrite")
            assertNotNull(result, "updatePreference 후 기억이 존재해야 한다")
            assertEquals("English", result!!.preferences["language"], "선호도 'language'가 'English'로 갱신되어야 한다")
        }

        @Test
        fun `기억이 없는 사용자에게 updatePreference을 호출하면 새 기억이 생성된다`() = runTest {
            manager.updatePreference("new-pref-user", "theme", "dark")

            val result = manager.get("new-pref-user")
            assertNotNull(result, "updatePreference이 신규 사용자의 기억을 생성해야 한다")
            assertEquals("dark", result!!.preferences["theme"], "신규 사용자의 선호도가 저장되어야 한다")
        }
    }

    @Nested
    inner class DefaultConstants {

        @Test
        fun `DEFAULT_MAX_PROMPT_INJECTION_CHARS는 1000이다`() {
            assertEquals(
                1000,
                UserMemoryManager.DEFAULT_MAX_PROMPT_INJECTION_CHARS,
                "DEFAULT_MAX_PROMPT_INJECTION_CHARS의 기본값은 1000이어야 한다"
            )
        }
    }
}
