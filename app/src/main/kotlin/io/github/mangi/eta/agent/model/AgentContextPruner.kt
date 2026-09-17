package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 较早期工具结果的清理。
 *
 * 深处的工具原始结果模型通常不再需要，把它们换成占位内容是最安全、最轻量的上下文压缩方式：
 * 它不动历史里的助手推理与用户输入，也不改写摘要，只让请求体变短。清理只作用于请求视图，
 * 会话记录与归档仍保留完整结果。
 */
internal object AgentContextPruner {
    const val PRUNED_NOTE = "较早的工具结果已清理；需要原始内容时请重新调用对应工具。"

    /** 已经是占位大小以下的结果不再处理，避免反复改写同一处。 */
    private const val MIN_PRUNABLE_CHARS = 200

    /** 最近这些 token 内的工具结果一律不裁剪。 */
    const val PROTECTED_TOKENS = 40_000

    /**
     * 把除最近 [keepRecentToolResults] 条以外的工具结果替换为占位内容，返回被清理的条数。
     * [keepRecentToolResults] 小于 0 表示不清理。
     */
    fun prune(messages: JSONArray, keepRecentToolResults: Int): Int {
        if (keepRecentToolResults < 0) return 0
        val protectedIndexes = protectedIndexes(messages)
        val toolIndexes = (0 until messages.length()).filter { index ->
            messages.optJSONObject(index)?.optString("role") == "tool"
        }
        var pruned = 0
        toolIndexes.dropLast(keepRecentToolResults).forEach { index ->
            if (index in protectedIndexes) return@forEach
            val message = messages.optJSONObject(index) ?: return@forEach
            val content = message.optString("content")
            if (content.length <= MIN_PRUNABLE_CHARS) return@forEach
            message.put("content", placeholder())
            pruned += 1
        }
        return pruned
    }

    /**
     * 最近的 [PROTECTED_TOKENS] token 是工作区，落到这个范围内的工具结果一律不裁剪。
     * 只按条数保留会在大结果场景下失效：一条几万 token 的输出可能刚好被裁掉，
     * 而模型正在用它推进任务。主流客户端（OpenCode 等）同样按 token 划分保护区。
     */
    private fun protectedIndexes(messages: JSONArray): Set<Int> {
        val result = mutableSetOf<Int>()
        val noTools = JSONArray()
        var accumulated = 0
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            accumulated += AgentContextBudget.rawEstimate(JSONArray().put(message), noTools)
            if (accumulated > PROTECTED_TOKENS) break
            result += index
        }
        return result
    }

    fun placeholder(): String = JSONObject()
        .put("ok", true)
        .put("pruned", true)
        .put("note", PRUNED_NOTE)
        .toString()

    /** 请求视图必须是副本：清理不能改到会话历史与归档。 */
    fun copyOf(messages: JSONArray): JSONArray = JSONArray(messages.toString())
}
