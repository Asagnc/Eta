package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 系统副屏工具 schema。副屏由系统合成，主屏的前台应用与输入不受影响。 */
internal object AgentVirtualScreenToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "virtual_screen",
                description = "操作系统副屏（系统级 overlay display），让应用运行在主屏之外，主屏可继续正常使用。" +
                    "先 create 建屏，再 launch 投应用，用 input 操作、capture 截图查看；不需要时用 destroy 关闭。" +
                    "display 编号由系统分配且每次创建都会变化，因此不要手工传坐标空间之外的 display 参数。" +
                    "capture 是唯一能观察副屏的手段：副屏没有可读取的 UI 节点树。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("create")
                                            .put("list")
                                            .put("destroy")
                                            .put("launch")
                                            .put("input")
                                            .put("capture"),
                                    )
                                    .put("description", "本次唯一执行的副屏动作。")
                            )
                            .put(
                                "width",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "create 的副屏宽度，320 到 2560，默认 1280。")
                            )
                            .put(
                                "height",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "create 的副屏高度，320 到 2560，默认 720。")
                            )
                            .put(
                                "dpi",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "create 的副屏密度，72 到 640，默认 160。")
                            )
                            .put(
                                "package",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "launch 要投到副屏的应用包名。")
                            )
                            .put(
                                "activity",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "launch 的可选 Activity；省略时使用该包的启动入口。")
                            )
                            .put(
                                "type",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("tap").put("swipe").put("key").put("text"))
                                    .put("description", "input 的注入类型。")
                            )
                            .put("x", JSONObject().put("type", "integer").put("description", "tap 的横坐标。"))
                            .put("y", JSONObject().put("type", "integer").put("description", "tap 的纵坐标。"))
                            .put("x1", JSONObject().put("type", "integer").put("description", "swipe 起点横坐标。"))
                            .put("y1", JSONObject().put("type", "integer").put("description", "swipe 起点纵坐标。"))
                            .put("x2", JSONObject().put("type", "integer").put("description", "swipe 终点横坐标。"))
                            .put("y2", JSONObject().put("type", "integer").put("description", "swipe 终点纵坐标。"))
                            .put(
                                "duration_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "swipe 时长，100 到 2000，默认 300。")
                            )
                            .put(
                                "key",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "key 的按键名，例如 KEYCODE_BACK。")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "text 要注入的文本，最多 200 个字符；非 ASCII 字符能否输入取决于副屏的输入法。")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }
}
