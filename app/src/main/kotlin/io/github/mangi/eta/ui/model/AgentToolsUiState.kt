package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentToolsUiState(
    val groups: List<ToolGroupUi>,
    val evaluation: AgentEvaluationUi = AgentEvaluationUi(),
)

/**
 * 工具页上的评测状态：跑的时候显示进度，跑完显示上一次的汇总。
 *
 * 评测会真实调用模型并消耗 token，所以这里只暴露“在跑/跑完”，不提供中途改任务集的能力。
 */
@Immutable
data class AgentEvaluationUi(
    val running: Boolean = false,
    val taskCount: Int = 0,
    val finishedCount: Int = 0,
    val passedCount: Int = 0,
    val currentTaskId: String = "",
    /** 最近一次完成的汇总文案；空表示还没跑过。 */
    val summary: String = "",
)

@Immutable
data class ToolGroupUi(
    val id: String,
    val title: String,
    val tools: List<ToolItemUi>,
)

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
)
