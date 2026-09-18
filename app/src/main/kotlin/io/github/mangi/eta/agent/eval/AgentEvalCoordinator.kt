package io.github.mangi.eta.agent.eval

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * 按任务集依次发起真实 run，收集指标并汇总落盘。
 *
 * 复用普通 run 通道而不是新开一条 IPC：每个任务用独立 runId，上下文互不干扰，
 * 也不会进入用户正在使用的会话。本方法是阻塞的，调用方负责切到 IO 线程。
 */
internal class AgentEvalCoordinator(
    context: Context,
    /** 评测期间沿用同一份配置：中途换模型会让不同任务的数字不可比。 */
    private val config: AgentModelClient.ModelConfig,
) {
    private val appContext = context.applicationContext
    private val store = AgentEvalReportStore(appContext)

    /** 私有目录里放过 `evals/tasks.json` 就用它，否则用内置任务集。 */
    fun availableTasks(): List<AgentEvalTask> {
        val file = store.taskSetFile()
        if (file.isFile) {
            val parsed = AgentEvalTaskSet.parse(file.readText())
            if (parsed.isNotEmpty()) return parsed
        }
        return AgentEvalTaskSet.BUILT_IN
    }

    fun recentReports(limit: Int = 10): List<AgentEvalReport> = store.recent(limit)

    fun run(
        tasks: List<AgentEvalTask>,
        label: String,
        onProgress: (index: Int, total: Int, result: AgentEvalTaskResult) -> Unit = { _, _, _ -> },
    ): AgentEvalReport {
        val report = AgentEvalRunner(runTask = ::executeTask).run(tasks, label, onProgress)
        store.save(report)
        store.prune()
        return report
    }

    /**
     * 跑一个任务：订阅事件拿度量快照与用过的工具，run 结束后换算成原始指标。
     * 快照缺失或 run 本身失败都按失败原因回报，不影响后续任务继续跑。
     */
    private fun executeTask(task: AgentEvalTask, runId: String): AgentEvalRawOutcome {
        val statsRef = AtomicReference<String>()
        val usedTools = linkedSetOf<String>()
        val trace = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        val result = AgentRuntimeClient(appContext, AndroidAgentLogger).run(
            request = AgentRuntimeWire.RunRequest(
                runId = runId,
                prompt = task.prompt,
                config = config,
                images = emptyList(),
                operation = AgentRuntimeWire.OP_EVAL,
            ),
            onEvent = { event ->
                when (event) {
                    is AgentEvent.RunStatsReported -> statsRef.set(event.statsJson)
                    is AgentEvent.ToolStarted -> {
                        usedTools += event.name
                        trace += "round=${event.round} tool=${event.name} args=${event.argsPreview}"
                    }
                    else -> Unit
                }
            },
        )
        store.saveTrace(task.id, trace)
        return AgentEvalMetrics.fromStats(
            statsJson = statsRef.get().orEmpty(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            completed = result.ok,
            failureCode = if (result.ok) null else "EVAL_RUN_FAILED",
            note = result.error.orEmpty(),
        ).copy(usedTools = if (usedTools.isEmpty()) emptySet() else usedTools)
    }

}
