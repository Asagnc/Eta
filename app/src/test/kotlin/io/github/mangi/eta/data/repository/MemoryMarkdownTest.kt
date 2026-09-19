package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 纯逻辑测试：本地（aarch64）与 CI 都能跑，章节边界规则不依赖 Robolectric。 */
class MemoryMarkdownTest {
    private val content = "# 核心记忆\n旧偏好\n## 项目\n旧项目\n## 其他\n保留"

    @Test
    fun findsSectionByTitleIgnoringLevel() {
        val lines = content.split('\n')

        val section = MemoryMarkdown.findSection(lines, "项目")

        assertEquals("## 项目", section?.headingText)
        assertEquals(2, section?.startIndex)
        // 下一个同级标题（## 其他）之前结束。
        assertEquals(4, section?.endIndex)
        assertNull(MemoryMarkdown.findSection(lines, "不存在的章节"))
    }

    @Test
    fun topLevelSectionSpansNestedSubsections() {
        val section = MemoryMarkdown.findSection(content.split('\n'), "核心记忆")

        assertEquals("# 核心记忆", section?.headingText)
        assertEquals(1, section?.level)
        assertEquals(0, section?.startIndex)
        assertEquals(6, section?.endIndex)
    }

    @Test
    fun sectionIndexCarriesInclusiveLineRanges() {
        assertEquals(
            "# 核心记忆  [L1-6]\n## 项目  [L3-4]\n## 其他  [L5-6]",
            MemoryMarkdown.sectionIndex(content),
        )
        assertEquals("", MemoryMarkdown.sectionIndex("没有标题的正文"))
    }

    @Test
    fun headingTextNormalizesLevelAndTitleStripsMarkers() {
        assertEquals("设备", MemoryMarkdown.title("## 设备"))
        assertEquals("设备", MemoryMarkdown.title("设备"))
        assertEquals("## 设备", MemoryMarkdown.headingText("设备"))
        assertEquals("### 设备", MemoryMarkdown.headingText("  ###   设备  "))
    }

    @Test
    fun headingRequiresMarkersAndText() {
        assertNull(MemoryMarkdown.heading("####"))
        assertNull(MemoryMarkdown.heading("#没有空格"))
        assertNull(MemoryMarkdown.heading("普通正文"))
        assertEquals(2, MemoryMarkdown.heading("## 设备  ")?.level)
        assertEquals("设备", MemoryMarkdown.heading("## 设备  ")?.title)
    }
}
