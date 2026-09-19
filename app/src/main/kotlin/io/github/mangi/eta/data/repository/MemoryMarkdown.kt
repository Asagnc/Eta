package io.github.mangi.eta.data.repository

/**
 * MEMORY.md 的 Markdown 标题解析：章节定位、标题规范化，以及带行号范围的标题索引。
 *
 * 章节语义：一个章节从它的标题行开始，到下一个同级或更高级标题之前结束（`# 核心记忆` 会覆盖它下面所有 `##` 小节）。
 */
internal object MemoryMarkdown {
    private val HEADING_LINE = Regex("^(#{1,6})[ \\t]+(\\S.*?)[ \\t]*$")
    private const val DEFAULT_LEVEL = 2
    private const val INDEX_LIMIT_CHARS = 4_000

    data class Heading(val level: Int, val title: String, val text: String)

    data class Section(
        val headingText: String,
        val title: String,
        val level: Int,
        val startIndex: Int,
        val endIndex: Int,
    )

    /** 解析标题行；不是标题、或 `#` 后没有标题文本时返回 null。 */
    fun heading(line: String): Heading? {
        val match = HEADING_LINE.find(line) ?: return null
        val level = match.groupValues[1].length
        val title = match.groupValues[2].trim()
        if (title.isEmpty()) return null
        return Heading(level, title, "#".repeat(level) + " " + title)
    }

    /** 去掉 `#` 前缀与空白后的标题名，用于跨层级比较（`## 设备` 与 `设备` 等价）。 */
    fun title(raw: String): String = raw.trim().dropWhile { character -> character == '#' }.trim()

    /** 规范化标题行；调用方没写 `#` 时按二级标题处理。 */
    fun headingText(raw: String): String {
        val trimmed = raw.trim()
        val match = HEADING_LINE.find(trimmed)
        if (match != null) return "#".repeat(match.groupValues[1].length) + " " + match.groupValues[2].trim()
        return "#".repeat(DEFAULT_LEVEL) + " " + title(trimmed)
    }

    fun findSection(lines: List<String>, title: String): Section? {
        for (index in lines.indices) {
            val heading = heading(lines[index]) ?: continue
            if (!heading.title.equals(title, ignoreCase = true)) continue
            val endIndex = ((index + 1) until lines.size).firstOrNull { candidate ->
                val next = heading(lines[candidate])
                next == null || next.level <= heading.level
            } ?: lines.size
            return Section(heading.text, heading.title, heading.level, index, endIndex)
        }
        return null
    }

    /** 带行号范围的标题索引，例如 `## 设备  [L12-45]`；超出上限时按行截断。 */
    fun sectionIndex(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val headings = lines.mapIndexedNotNull { index, line ->
            heading(line)?.let { parsed -> Triple(index, parsed.level, parsed.text) }
        }
        if (headings.isEmpty()) return ""
        val entries = headings.mapIndexed { position, (startIndex, level, text) ->
            val nextIndex = headings.drop(position + 1).firstOrNull { it.second <= level }?.first
            // nextIndex 是下一个标题的 0 起下标，恰好等于它上一行的 1 起行号；没有下一个标题时到文件末行。
            val endLine = nextIndex ?: lines.size
            "$text  [L${startIndex + 1}-$endLine]"
        }
        val joined = entries.joinToString("\n")
        if (joined.length <= INDEX_LIMIT_CHARS) return joined
        val cut = joined.lastIndexOf('\n', INDEX_LIMIT_CHARS - 2).coerceAtLeast(1)
        return joined.take(cut) + "\n…"
    }
}
