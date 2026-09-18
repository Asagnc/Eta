package io.github.mangi.eta.agent.eval

import org.json.JSONObject

/**
 * 评测任务集的解析。
 *
 * 接受仓库 `evals/tasks.json` 的结构（`version`/`note`/`tasks`），只取内部评测用得上的字段：
 * 外部评测关心的 `expect_arguments` 与 mock `tool_result` 在真实执行里没有意义，忽略即可。
 */
internal object AgentEvalTaskSet {
    const val DEFAULT_MAX_ROUNDS = 8

    /**
     * 内置任务集：与仓库 `evals/tasks.json` 同源，13 个任务都在这台设备上真实可执行。
     * App 私有目录里存在 `evals/tasks.json` 时以那份为准，方便换成自己关心的任务。
     */
    val BUILT_IN: List<AgentEvalTask> = listOf(
        AgentEvalTask(
            id = "device-status",
            category = "device",
            prompt = "看一下手机现在的电量、内存和存储占用，再说说还能不能装下一个 8GB 的游戏。",
            expectTools = listOf("device_status", "top_storage_apps", "get_current_context", "get_health_summary"),
            tier = AgentEvalTask.TIER_LIGHT,
        ),
        AgentEvalTask(
            id = "current-time-place",
            category = "device",
            prompt = "现在几点？我大概在哪个位置？",
            expectTools = listOf("get_current_context", "get_current_location", "device_status"),
            tier = AgentEvalTask.TIER_LIGHT,
        ),
        AgentEvalTask(
            id = "locate-concurrency-constant",
            category = "code",
            prompt = "在 Eta 源码里找出 AgentLoop 的并发上限常量定义，把定义那一行和它前后的实现一起给我。",
            expectTools = listOf("search_code", "read_file", "list_directory", "run_stats"),
        ),
        AgentEvalTask(
            id = "edit-config-default",
            category = "code",
            prompt = "把 Prefs.kt 里 agent_parallel_tool_limit 的默认值从 4 改成 8，改完告诉我改动了什么。",
            expectTools = listOf("edit_file", "read_file", "search_code"),
        ),
        AgentEvalTask(
            id = "apk-static-scan",
            category = "security",
            prompt = "对 /workspace/test-base.apk 做一次静态初筛：包名、权限、有没有 native 库和 dex 数。",
            expectTools = listOf("terminal", "read_file", "list_directory", "search_code"),
        ),
        AgentEvalTask(
            id = "capture-env-status",
            category = "security",
            prompt = "先确认一下当前的抓包环境是什么状态，再决定要不要重建 CA 注入。",
            expectTools = listOf("terminal", "read_file"),
        ),
        AgentEvalTask(
            id = "skill-declared-command",
            category = "skill",
            prompt = "用已安装 Skill 里声明好的命令把抓包环境搭起来，不要把整篇 SKILL.md 读进来。",
            expectTools = listOf("skills_list", "skills_read", "skills_run", "terminal"),
        ),
        AgentEvalTask(
            id = "observe-clickable-nodes",
            category = "ui",
            prompt = "现在屏幕上都有哪些可以点的按钮？列出来就行。",
            expectTools = listOf("observe_screen", "tap_element", "read_image", "wait_for_text"),
            tier = AgentEvalTask.TIER_LIGHT,
        ),
        AgentEvalTask(
            id = "open-wechat",
            category = "ui",
            prompt = "帮我打开微信。",
            expectTools = listOf("search_apps", "launch_app", "open_uri"),
            tier = AgentEvalTask.TIER_LIGHT,
        ),
        AgentEvalTask(
            id = "plan-and-track",
            category = "agent",
            prompt = "帮我排一下顺序并跟踪进度：先查设备状态，再改一处配置，最后跑一遍单测。",
            expectTools = listOf("task_plan", "device_status", "edit_file", "terminal"),
            maxRounds = 12,
        ),
        AgentEvalTask(
            id = "recall-build-command",
            category = "memory",
            prompt = "我之前把 Eta 的构建命令记在哪了？",
            expectTools = listOf("memory_get", "search_code", "read_file"),
            tier = AgentEvalTask.TIER_LIGHT,
        ),
        AgentEvalTask(
            id = "self-run-stats",
            category = "meta",
            prompt = "看一下这次 run 到现在调了多少次工具，哪一类最多。",
            expectTools = listOf("run_stats", "search_code", "list_directory"),
            tier = AgentEvalTask.TIER_LIGHT,
        ),
        AgentEvalTask(
            id = "logcat-model-failure",
            category = "device",
            prompt = "查一下最近的系统日志，有没有模型请求失败的痕迹。",
            expectTools = listOf("get_logcat", "terminal", "read_file"),
        ),
    )

    fun parse(content: String): List<AgentEvalTask> =
        runCatching { parse(JSONObject(content)) }.getOrDefault(emptyList())

    fun parse(root: JSONObject): List<AgentEvalTask> {
        val tasks = root.optJSONArray("tasks") ?: return emptyList()
        return (0 until tasks.length()).mapNotNull { index ->
            val item = tasks.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val prompt = item.optString("prompt").trim()
            if (id.isEmpty() || prompt.isEmpty()) return@mapNotNull null
            val tools = item.optJSONArray("tools")
            AgentEvalTask(
                id = id,
                category = item.optString("category").trim(),
                prompt = prompt,
                expectTools = if (tools == null) {
                    emptyList()
                } else {
                    (0 until tools.length())
                        .mapNotNull { toolIndex -> tools.optString(toolIndex).takeIf { it.isNotBlank() } }
                },
                maxRounds = item.optInt("max_rounds", DEFAULT_MAX_ROUNDS).coerceIn(1, 40),
                tier = item.optString("tier").trim().takeIf { it.isNotBlank() } ?: AgentEvalTask.TIER_FULL,
            )
        }
    }
}
