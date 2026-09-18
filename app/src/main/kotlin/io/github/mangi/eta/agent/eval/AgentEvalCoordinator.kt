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

    /**
     * 私有目录里放过 `evals/tasks.json` 就用它，否则用内置任务集。
     *
     * tier 非空时只保留该层任务：light 是 2–3 轮就能过的快速用例，日常回归只跑这一层，
     * 免得一次评估既慢又贵；full 是完整任务集，需要时再跑。
     */
    fun availableTasks(tier: String? = null): List<AgentEvalTask> {
        val file = store.taskSetFile()
        val all = if (file.isFile) {
            val parsed = AgentEvalTaskSet.parse(file.readText())
            if (parsed.isNotEmpty()) parsed else AgentEvalTaskSet.BUILT_IN
        } else {
            AgentEvalTaskSet.BUILT_IN
        }
        return if (tier.isNullOrBlank()) all else all.filter { it.tier == tier }
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
     * 服务端不可用（HTTP 5xx、网关超时、连接被断开）不是任务本身的问题：
     * 归到 EVAL_INFRA_UNAVAILABLE，汇总时单列，不参与通过率。
     */
    private fun classifyRunFailure(error: String): String {
        val text = error.lowercase()
        return when {
            "503" in text || "502" in text || "504" in text -> EVAL_INFRA_UNAVAILABLE
            "模型服务暂时不可用" in error -> EVAL_INFRA_UNAVAILABLE
            "timeout" in text || "timed out" in text -> EVAL_INFRA_UNAVAILABLE
            "connection reset" in text || "connection closed" in text -> EVAL_INFRA_UNAVAILABLE
            "连接已断开" in error -> EVAL_INFRA_UNAVAILABLE
            else -> "EVAL_RUN_FAILED"
        }
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
            failureCode = if (result.ok) null else classifyRunFailure(result.error.orEmpty()),
            note = result.error.orEmpty(),
        ).copy(usedTools = if (usedTools.isEmpty()) emptySet() else usedTools)
    }

}
