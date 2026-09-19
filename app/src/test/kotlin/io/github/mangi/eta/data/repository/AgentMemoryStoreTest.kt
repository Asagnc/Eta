package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("安全内容")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# 核心记忆\n喜欢 Kotlin\n## 项目\nEta Agent\n其他")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## 项目"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Eta Agent"))
        assertTrue(search.content.contains("3: ## 项目"))
        assertTrue(search.content.contains("5: 其他"))
        assertFalse(search.hasMore)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "新偏好",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n## 项目\n旧项目", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## 冲突追加"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("冲突追加"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## 新章节\n内容"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("旧项目"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    @Test
    fun upsertReplacesTheMatchingSectionInPlace() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目\n## 其他\n保留")

        val replaced = store.mutate(
            AgentMemoryMutation.UpsertSection(
                revision = initial.revision,
                heading = "项目",
                content = "新项目\n第二行",
            ),
        ) as AgentMemoryWriteResult.Success

        assertEquals("# 核心记忆\n旧偏好\n## 项目\n新项目\n第二行\n## 其他\n保留", replaced.snapshot.content)
        assertEquals("## 项目", replaced.section?.heading)
        assertTrue(replaced.section?.replaced == true)
        assertEquals(3, replaced.section?.startLine)
        assertEquals(5, replaced.section?.endLine)
    }

    @Test
    fun upsertMatchesTitleIgnoringLevelAndCreatesMissingSectionAtTheEnd() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好")

        val replaced = store.mutate(
            AgentMemoryMutation.UpsertSection(initial.revision, "## 核心记忆", "新偏好"),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好", replaced.snapshot.content)

        val inserted = store.mutate(
            AgentMemoryMutation.UpsertSection(replaced.snapshot.revision, "新主题", "内容"),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n\n## 新主题\n内容", inserted.snapshot.content)
        assertTrue(inserted.section?.replaced == false)
        assertEquals("## 新主题", inserted.section?.heading)
    }

    @Test
    fun upsertRefusesToSilentlyDropNestedSections() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val failure = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.UpsertSection(initial.revision, "核心记忆", "只剩一句话"))
        }
        assertEquals("MEMORY_SECTION_NESTED", failure.code)
        assertEquals(initial.revision, store.snapshot().revision)
        assertTrue(store.snapshot().content.contains("## 项目"))
    }

    @Test
    fun omittedRevisionWritesAgainstTheLatestContent() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好")
        val appendedFirst = store.mutate(AgentMemoryMutation.Append(initial.revision, "## 追加\n内容"))
            as AgentMemoryWriteResult.Success

        // revision = null 表示不做并发检查：旧 revision 也不会被拒，直接按最新内容写入。
        val appended = store.mutate(AgentMemoryMutation.Append(null, "## 后写入\n内容")) as AgentMemoryWriteResult.Success

        assertTrue(appended.snapshot.content.contains("## 追加"))
        assertTrue(appended.snapshot.content.contains("## 后写入"))
        assertTrue(appended.snapshot.revision != appendedFirst.snapshot.revision)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
