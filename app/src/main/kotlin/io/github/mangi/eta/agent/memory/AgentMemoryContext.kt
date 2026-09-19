package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.MemoryMarkdown

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    /** 自动注入的记忆内容：能装进注入预算时是全文，否则是 # 核心记忆 章节。 */
    val injectedContent: String,
    /** injectedContent 是否为记忆全文（为真时不需要再为了解记忆调用 memory_get）。 */
    val injectedFull: Boolean,
    val injectedTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            injectedContent = "",
            injectedFull = false,
            injectedTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val content = snapshot.content
        // 装得下就注入全文：一轮 system 消息的成本不变，却省掉「读不全 → 再 memory_get」的往返。
        val full = content.isNotBlank() && content.length <= coreBudget
        val core = extractCore(content)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            injectedContent = if (full) content else core.take(coreBudget),
            injectedFull = full,
            injectedTruncated = !full && core.length > coreBudget,
            headingIndex = MemoryMarkdown.sectionIndex(content),
            coreBudgetChars = coreBudget,
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private const val CORE_HEADING = "# 核心记忆"
    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
