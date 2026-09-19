package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsWholeFileWhenItFitsTheBudget() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应再按需读取"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(content, context.injectedContent)
        assertTrue(context.injectedFull)
        assertFalse(context.injectedTruncated)
    }

    @Test
    fun headingIndexCarriesLineRanges() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n细节"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(
            "# 核心记忆  [L1-4]\n## 关系  [L3-4]\n# 详细背景  [L5-6]",
            context.headingIndex,
        )
    }

    @Test
    fun oversizedFileFallsBackToTruncatedCoreSection() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.injectedContent.length)
        assertFalse(context.injectedFull)
        assertTrue(context.injectedTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun oversizedFileWithoutCoreHeadingIsIndexedButNotInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n" + "只应按需读取的细节".repeat(2_000)),
            128_000,
        )

        assertEquals("", context.injectedContent)
        assertFalse(context.injectedFull)
        assertFalse(context.injectedTruncated)
        assertEquals("# 项目  [L1-2]", context.headingIndex)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
