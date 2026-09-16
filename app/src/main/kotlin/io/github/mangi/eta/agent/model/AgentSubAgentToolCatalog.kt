package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 子智能体与多视角工具的 schema。
 *
 * 两者都是同一套受限子 loop 的入口：`delegate` 是单个角色的子任务，`multi_perspective`
 * 是并行派生多个隔离上下文的角色，再由主 loop 汇总。它们只在显式开启子智能体时出现。
 */
internal object AgentSubAgentToolCatalog {
    const val DELEGATE = "delegate"
    const val MULTI_PERSPECTIVE = "multi_perspective"

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = DELEGATE,
                    description = "把一个可以独立完成的子任务交给受限子智能体：它有自己的上下文，只能用文件检索类工具，" +
                        "只回一份摘要，过程不进入当前上下文。适合同时要翻很多文件、多个方向都查一遍的检索型子任务；" +
                        "需要写文件、跑命令或操作设备时不要用它。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "task",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 2_000)
                                        .put("description", "子任务与验收标准；规格越具体，产出越可靠。")
                                )
                                .put(
                                    "role",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 60)
                                        .put("description", "该子智能体扮演的角色名，例如 检索、日志分析；默认 检索。")
                                )
                                .put(
                                    "context",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 4_000)
                                        .put("description", "可选背景（已知路径、约束），只发给这个子智能体。")
                                )
                        )
                        .put("required", JSONArray().put("task"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = MULTI_PERSPECTIVE,
                    description = "并行派生多个互相隔离的角色，各自独立作答，再由当前 loop 汇总对照。" +
                        "角色之间不共享中间推理，否则会退化成同一份意见的不同措辞；汇总与校验由调用方负责。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()
                            .put(
                                "topic",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 2_000)
                                    .put("description", "需要多角度回答的问题或待审对象。")
                            )
                            .put(
                                "roles",
                                JSONObject()
                                    .put("type", "array")
                                    .put("items", JSONObject().put("type", "string").put("maxLength", 60))
                                    .put("minItems", 2)
                                    .put("maxItems", 4)
                                    .put("uniqueItems", true)
                                    .put("description", "2-4 个互不相同的角色名，例如 攻击视角、防御视角、合规视角。")
                            )
                            .put(
                                "brief",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 4_000)
                                    .put("description", "可选补充要求（输出格式、判定标准），三个角色共用。")
                            )
                        )
                        .put("required", JSONArray().put("topic").put("roles")))
            )
    }
}
