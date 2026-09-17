package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** usage 只校准同一模型的请求估算，不把累计计费用量当作窗口占用。 */
internal class AgentContextBudget(private val window: Int?) {
    /** 模型上下文窗口；为空表示当前 provider 没有声明窗口，此时不做占用提示。 */
    val windowTokens: Int?
        get() = window

    private var calibration = 1.0
    private var observedInputTokens = 0

    /**
     * 用服务端回报的真实 input token 校准本地字符估算。
     *
     * 校准系数双向生效：中文与 JSON 密集的会话里，字符估算（非 ASCII 每字符按 1 token）
     * 明显高于真实 token 数，如果只允许放大系数，估算就会长期虚高，
     * 远未触及真实窗口就触发占用提示与压缩——「1M 窗口只用了一半就说快满了」即来自这里。
     */
    fun observe(usage: AgentTokenUsage?, requestEstimate: Int) {
        val input = usage?.inputTokens ?: usage?.contextTokens ?: return
        if (input > 0 && requestEstimate > 0) {
            calibration = (input.toDouble() / requestEstimate).coerceIn(MIN_CALIBRATION, MAX_CALIBRATION)
            observedInputTokens = input
        }
    }

    /**
     * 估算只负责增量：结果不会低于上一次请求由服务端回报的真实 input token 数，
     * 避免估算虚低时把已经发生的上下文占用当成可用空间。
     */
    fun estimate(messages: JSONArray, tools: JSONArray): Int {
        val estimated = ceil(rawEstimate(messages, tools) * calibration).toInt()
        return maxOf(estimated, observedInputTokens)
    }

    fun shouldCompact(tokens: Int): Boolean =
        window?.takeIf { it > 0 }?.let { tokens >= it * TRIGGER_RATIO } == true

    fun exceedsWindow(tokens: Int): Boolean = window?.takeIf { it > 0 }?.let { tokens >= it } == true

    companion object {
        /**
         * 压缩触发点对齐主流实现：Codex CLI 把生效窗口上限定在 90%，
         * 社区实测自动压缩的最优区间是 85–90%，95% 往往来不及压缩就溢出。
         */
        const val TRIGGER_RATIO = 0.90
        /** 估算校准的下限与上限：单次异常 usage 不应让估算失控。 */
        const val MIN_CALIBRATION = 0.25
        const val MAX_CALIBRATION = 8.0
        const val RECENT_MESSAGES = 4
        const val RECENT_RATIO = 0.20
        const val MAX_OVERFLOW_ATTEMPTS = 3

        fun textTokens(text: String): Int {
            var ascii = 0
            var other = 0
            text.codePoints().forEach { if (it < 128) ascii++ else other++ }
            return (ascii + 2) / 3 + other
        }

        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = textTokens(tools.toString()) + 16
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val copy = JSONObject()
                message.keys().forEach { key -> if (key != "content") copy.put(key, message.get(key)) }
                val parts = message.optJSONArray("content")
                if (parts != null) {
                    val text = JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") in setOf("image_url", "input_image", "image")) {
                            tokens += 4096
                        } else text.put(part)
                    }
                    copy.put("content", text)
                } else copy.put("content", message.opt("content"))
                tokens += textTokens(copy.toString()) + 8
            }
            return tokens
        }
    }
}
