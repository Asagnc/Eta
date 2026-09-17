package io.github.mangi.eta.agent.eval

/**
 * 评测编排：按顺序跑完任务集，把原始指标判定成结果并汇总。
 *
 * 真正的执行由调用方注入（runtime 侧才知道怎么跑一次 run），这里只留编排与判定，
 * 所以能脱离 Android 环境单独验证。
 */
internal class AgentEvalRunner(
    private val runTask: (task: AgentEvalTask, runId: String) -> AgentEvalRawOutcome,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun run(
        tasks: List<AgentEvalTask>,
        label: String,
        onTaskFinished: (index: Int, total: Int, result: AgentEvalTaskResult) -> Unit = { _, _, _ -> },
    ): AgentEvalReport {
        val startedAt = nowMillis()
        val results = mutableListOf<AgentEvalTaskResult>()
        tasks.forEachIndexed { index, task ->
            val runId = "eval-${task.id}-$index"
            val raw = runCatching { runTask(task, runId) }.getOrElse { failure ->
                AgentEvalRawOutcome(
                    rounds = 0,
                    inputTokens = 0,
                    outputTokens = 0,
                    toolCalls = 0,
                    toolFailures = 0,
                    usedTools = emptySet(),
                    completed = false,
                    elapsedMs = 0,
                    failureCode = "EVAL_TASK_THREW",
                    note = failure.message ?: failure.javaClass.simpleName,
                )
            }
            val result = judge(task, raw)
            results += result
            onTaskFinished(index, tasks.size, result)
        }
        return AgentEvalReport(label, startedAt, nowMillis(), results)
    }

    /**
     * 判定顺序：先看执行本身有没有失败，再看有没有跑完，再看有没有用到该用的工具，
     * 最后看轮次是否超标——同一份指标只归到一个原因，便于按失败类型聚合。
     */
    fun judge(task: AgentEvalTask, raw: AgentEvalRawOutcome): AgentEvalTaskResult {
        val failureCode = when {
            raw.failureCode != null -> raw.failureCode
            !raw.completed -> "EVAL_NOT_COMPLETED"
            task.expectTools.isNotEmpty() && raw.usedTools.none { it in task.expectTools } -> "EVAL_TOOL_MISS"
            raw.rounds > task.maxRounds -> "EVAL_TOO_MANY_ROUNDS"
            else -> null
        }
        return AgentEvalTaskResult(
            taskId = task.id,
            passed = failureCode == null,
            rounds = raw.rounds,
            inputTokens = raw.inputTokens,
            outputTokens = raw.outputTokens,
            toolCalls = raw.toolCalls,
            toolFailures = raw.toolFailures,
            elapsedMs = raw.elapsedMs,
            failureCode = failureCode,
            note = raw.note,
        )
    }
}
