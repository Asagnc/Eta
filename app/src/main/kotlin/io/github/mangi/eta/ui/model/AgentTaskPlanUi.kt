package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** 任务清单单项的状态；与 task_plan 工具接受的字符串一一对应。 */
@Immutable
internal enum class AgentTaskPlanStatus { PENDING, IN_PROGRESS, COMPLETED, INTERRUPTED }

/** 任务清单快照里的一项，由 task_plan 工具事件投影而来。 */
@Immutable
internal data class AgentTaskPlanItemUi(
    val id: String,
    val content: String,
    val status: AgentTaskPlanStatus,
)

internal object AgentTaskPlanCodec {
    /**
     * 解析事件里的清单快照。缺 id 或缺内容的项直接跳过——这些项在写入前已被工具拒绝，
     * 出现即说明数据来自更早的版本，此处不补默认值。
     */
    fun decode(planJson: String): List<AgentTaskPlanItemUi> = runCatching {
        val array = JSONArray(planJson)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val content = item.optString("content").trim()
            if (id.isEmpty() || content.isEmpty()) return@mapNotNull null
            AgentTaskPlanItemUi(
                id = id,
                content = content,
                status = when (item.optString("status")) {
                    "completed" -> AgentTaskPlanStatus.COMPLETED
                    "in_progress" -> AgentTaskPlanStatus.IN_PROGRESS
                    "interrupted" -> AgentTaskPlanStatus.INTERRUPTED
                    else -> AgentTaskPlanStatus.PENDING
                },
            )
        }
    }.getOrDefault(emptyList())

    /** 写回存档：空清单返回空串，免得在库里躺一个没意义的 "[]"。 */
    fun encode(items: List<AgentTaskPlanItemUi>): String {
        if (items.isEmpty()) return ""
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("content", item.content)
                    .put("status", item.status.wireValue),
            )
        }
        return array.toString()
    }

    private val AgentTaskPlanStatus.wireValue: String
        get() = when (this) {
            AgentTaskPlanStatus.PENDING -> "pending"
            AgentTaskPlanStatus.IN_PROGRESS -> "in_progress"
            AgentTaskPlanStatus.COMPLETED -> "completed"
            AgentTaskPlanStatus.INTERRUPTED -> "interrupted"
        }
}
