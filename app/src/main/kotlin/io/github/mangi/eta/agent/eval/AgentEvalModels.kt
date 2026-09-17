package io.github.mangi.eta.agent.eval

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个评测任务。
 *
 * 外部评测（仓库里的 `evals/run_eval.py`）用 mock 结果检查“首个工具与参数选得对不对”；
 * 这里的任务跑的是真实工具链，所以判定的是“跑完没有、效率如何、有没有用到该用的工具”。
 */
internal data class AgentEvalTask(
    val id: String,
    val category: String,
    val prompt: String,
    /** 期望至少用到其中之一的工具；为空表示不检查。 */
    val expectTools: List<String> = emptyList(),
    /** 轮次上限：超过它说明这条路走得太绕，即使最终跑通也算不达标。 */
    val maxRounds: Int = 8,
)

/** 执行一个任务后拿到的原始指标；判定留给 [AgentEvalRunner]，便于单独验证规则。 */
internal data class AgentEvalRawOutcome(
    val rounds: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val toolCalls: Int,
    val toolFailures: Int,
    val usedTools: Set<String>,
    val completed: Boolean,
    val elapsedMs: Long,
    val failureCode: String? = null,
    val note: String = "",
)

/** 一个任务的判定结果。 */
internal data class AgentEvalTaskResult(
    val taskId: String,
    val passed: Boolean,
    val rounds: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val toolCalls: Int,
    val toolFailures: Int,
    val elapsedMs: Long,
    val failureCode: String?,
    val note: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("task_id", taskId)
        .put("passed", passed)
        .put("rounds", rounds)
        .put("input_tokens", inputTokens)
        .put("output_tokens", outputTokens)
        .put("tool_calls", toolCalls)
        .put("tool_failures", toolFailures)
        .put("elapsed_ms", elapsedMs)
        .put("failure_code", failureCode ?: JSONObject.NULL)
        .put("note", note)

    companion object {
        fun fromJson(json: JSONObject): AgentEvalTaskResult = AgentEvalTaskResult(
            taskId = json.optString("task_id"),
            passed = json.optBoolean("passed"),
            rounds = json.optInt("rounds"),
            inputTokens = json.optLong("input_tokens"),
            outputTokens = json.optLong("output_tokens"),
            toolCalls = json.optInt("tool_calls"),
            toolFailures = json.optInt("tool_failures"),
            elapsedMs = json.optLong("elapsed_ms"),
            failureCode = json.optStringOrNull("failure_code"),
            note = json.optString("note"),
        )
    }
}

/** 一次评测的汇总；所有派生数字都在这里算，界面与对比脚本都不再各自推导。 */
internal data class AgentEvalReport(
    val label: String,
    val startedAt: Long,
    val finishedAt: Long,
    val results: List<AgentEvalTaskResult>,
) {
    val passedCount: Int get() = results.count { it.passed }
    val passRate: Double get() = if (results.isEmpty()) 0.0 else passedCount.toDouble() / results.size
    val averageRounds: Double get() = if (results.isEmpty()) 0.0 else results.sumOf { it.rounds }.toDouble() / results.size
    val totalInputTokens: Long get() = results.sumOf { it.inputTokens }
    val totalOutputTokens: Long get() = results.sumOf { it.outputTokens }
    val totalToolCalls: Int get() = results.sumOf { it.toolCalls }
    val totalToolFailures: Int get() = results.sumOf { it.toolFailures }
    val totalElapsedMs: Long get() = results.sumOf { it.elapsedMs }

    /** 失败原因分布：同一原因反复出现时，说明要改的是 harness 而不是某个任务。 */
    fun failureBreakdown(): Map<String, Int> = results
        .filterNot { it.passed }
        .map { it.failureCode ?: "UNKNOWN" }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedByDescending { (_, count) -> count }
        .toMap()

    fun toJson(): JSONObject {
        val tasks = JSONArray()
        results.forEach { tasks.put(it.toJson()) }
        val failures = JSONObject()
        failureBreakdown().forEach { (code, count) -> failures.put(code, count) }
        return JSONObject()
            .put("version", EVAL_REPORT_VERSION)
            .put("label", label)
            .put("started_at", startedAt)
            .put("finished_at", finishedAt)
            .put("task_count", results.size)
            .put("passed_count", passedCount)
            .put("pass_rate", passRate)
            .put("average_rounds", averageRounds)
            .put("total_input_tokens", totalInputTokens)
            .put("total_output_tokens", totalOutputTokens)
            .put("total_tool_calls", totalToolCalls)
            .put("total_tool_failures", totalToolFailures)
            .put("total_elapsed_ms", totalElapsedMs)
            .put("failure_breakdown", failures)
            .put("tasks", tasks)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentEvalReport {
            val tasks = json.optJSONArray("tasks") ?: JSONArray()
            return AgentEvalReport(
                label = json.optString("label"),
                startedAt = json.optLong("started_at"),
                finishedAt = json.optLong("finished_at"),
                results = (0 until tasks.length()).mapNotNull { index ->
                    tasks.optJSONObject(index)?.let(AgentEvalTaskResult::fromJson)
                },
            )
        }
    }
}

internal const val EVAL_REPORT_VERSION = 1

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
