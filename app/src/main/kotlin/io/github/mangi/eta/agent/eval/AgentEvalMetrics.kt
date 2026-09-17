package io.github.mangi.eta.agent.eval

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把一次 run 的度量快照转成评测用的原始指标。
 *
 * 快照来自 `AgentRunStats.snapshot()`；这里只做字段搬运与缺失兜底，
 * 判定规则留给 [AgentEvalRunner]，两边都能单独验证。
 */
internal object AgentEvalMetrics {
    fun fromStats(
        statsJson: String,
        elapsedMs: Long,
        completed: Boolean,
        failureCode: String? = null,
        note: String = "",
    ): AgentEvalRawOutcome {
        val stats = runCatching { JSONObject(statsJson) }.getOrNull()
        if (stats == null) {
            return AgentEvalRawOutcome(
                rounds = 0,
                inputTokens = 0,
                outputTokens = 0,
                toolCalls = 0,
                toolFailures = 0,
                usedTools = emptySet(),
                completed = completed,
                elapsedMs = elapsedMs,
                failureCode = failureCode ?: "EVAL_STATS_MISSING",
                note = note,
            )
        }
        val tokens = stats.optJSONObject("tokens") ?: JSONObject()
        val tools = stats.optJSONArray("tools") ?: JSONArray()
        val usedTools = (0 until tools.length())
            .mapNotNull { index -> tools.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() } }
            .toSet()
        return AgentEvalRawOutcome(
            rounds = stats.optInt("rounds"),
            inputTokens = tokens.optLong("input"),
            outputTokens = tokens.optLong("output"),
            toolCalls = stats.optInt("tool_calls"),
            toolFailures = stats.optInt("tool_failures"),
            usedTools = usedTools,
            completed = completed,
            elapsedMs = elapsedMs,
            failureCode = failureCode,
            note = note,
        )
    }
}
