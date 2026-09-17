package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 run 的工具调用与 token 度量。
 *
 * 只读白名单里的工具会并发执行，因此计数按工具名分桶并用原子量累加；每次读取都是快照，
 * 不重置累计值，同一 run 内可以反复取用。
 */
internal class AgentRunStats {
    private class Bucket {
        val calls = AtomicInteger()
        val failures = AtomicInteger()
        val totalMs = AtomicLong()
        val maxMs = AtomicLong()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val rounds = AtomicInteger()
    private val parallelBatches = AtomicInteger()
    private val parallelCalls = AtomicInteger()
    private val inputTokens = AtomicLong()
    private val outputTokens = AtomicLong()
    private val cachedTokens = AtomicLong()
    private val reasoningTokens = AtomicLong()
    private val contextTokens = AtomicInteger()
    private val prunedToolResults = AtomicInteger()
    private val contextNotices = AtomicInteger()

    val isEmpty: Boolean
        get() = rounds.get() == 0 && buckets.isEmpty()

    fun roundStarted() {
        rounds.incrementAndGet()
    }

    fun recordTool(name: String, durationMs: Long, success: Boolean) {
        val bucket = buckets.computeIfAbsent(name) { Bucket() }
        bucket.calls.incrementAndGet()
        if (!success) bucket.failures.incrementAndGet()
        val elapsed = durationMs.coerceAtLeast(0)
        bucket.totalMs.addAndGet(elapsed)
        bucket.maxMs.accumulateAndGet(elapsed) { current, value -> maxOf(current, value) }
    }

    fun recordParallelBatch(size: Int) {
        if (size <= 1) return
        parallelBatches.incrementAndGet()
        parallelCalls.addAndGet(size)
    }

    /**
     * 最近一次请求视图里被压成占位的工具结果条数。
     * 注意是覆盖而不是累加：同一个结果在后续每一轮都会被重新统计，累加会把数字放大成没有意义的值。
     */
    fun updatePrunedToolResults(count: Int) {
        prunedToolResults.set(count)
    }

    /** 上下文占用提示实际触发的次数；策略是否值得保留要看它。 */
    fun recordContextNotice() {
        contextNotices.incrementAndGet()
    }

    fun recordUsage(usage: AgentTokenUsage) {
        usage.inputTokens?.let { inputTokens.addAndGet(it.toLong()) }
        usage.outputTokens?.let { outputTokens.addAndGet(it.toLong()) }
        usage.cachedTokens?.let { cachedTokens.addAndGet(it.toLong()) }
        usage.reasoningTokens?.let { reasoningTokens.addAndGet(it.toLong()) }
        usage.contextTokens?.let(contextTokens::set)
    }

    /** 按调用次数降序的工具明细，最多 [MAX_LISTED_TOOLS] 项，其余合并进 `other_calls`。 */
    /**
     * 运行自省：只在本轮确实有值得注意的问题时返回文本，交给界面提示用户。
     * 空结果表示这次运行没有需要额外说明的地方，避免制造噪音。
     */
    fun selfReview(): String? {
        val issues = mutableListOf<String>()
        val failing = buckets.entries
            .filter { it.value.failures.get() >= 2 }
            .map { it.key }
            .sorted()
        if (failing.isNotEmpty()) {
            issues += "这些工具反复失败：" + failing.joinToString("、")
        }
        val busy = buckets.entries
            .filter { it.value.calls.get() >= 12 }
            .map { it.key }
            .sorted()
        if (busy.isNotEmpty()) {
            issues += "这些工具调用次数偏多（各 12 次以上），值得看一眼是不是有重复劳动：" +
                busy.joinToString("、")
        }
        if (prunedToolResults.get() >= 10) {
            issues += "最近一次请求里有 ${prunedToolResults.get()} 条较早的工具结果被压成占位，本轮信息量偏大"
        }
        if (contextNotices.get() >= 3) {
            issues += "上下文压力提示生效了 ${contextNotices.get()} 轮，系统一直在提醒精简"
        }
        return issues.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    fun snapshot(): JSONObject {
        val tools = JSONArray()
        var calls = 0
        var failures = 0
        var totalMs = 0L
        val sorted = buckets.entries.sortedWith(
            compareByDescending<Map.Entry<String, Bucket>> { it.value.calls.get() }.thenBy { it.key },
        )
        sorted.forEach { (name, bucket) ->
            val bucketCalls = bucket.calls.get()
            calls += bucketCalls
            failures += bucket.failures.get()
            totalMs += bucket.totalMs.get()
            if (tools.length() >= MAX_LISTED_TOOLS) return@forEach
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("calls", bucketCalls)
                    .put("failed", bucket.failures.get())
                    .put("total_ms", bucket.totalMs.get())
                    .put("max_ms", bucket.maxMs.get()),
            )
        }
        return JSONObject()
            .put("rounds", rounds.get())
            .put("tool_calls", calls)
            .put("tool_failures", failures)
            .put("tool_total_ms", totalMs)
            .put("parallel_batches", parallelBatches.get())
            .put("parallel_calls", parallelCalls.get())
            .put("pruned_tool_results", prunedToolResults.get())
            .put("context_notices", contextNotices.get())
            .put("distinct_tools", buckets.size)
            .put("listed_tools", tools.length())
            .put("tools", tools)
            .put(
                "tokens",
                JSONObject()
                    .put("input", inputTokens.get())
                    .put("output", outputTokens.get())
                    .put("cached", cachedTokens.get())
                    .put("reasoning", reasoningTokens.get())
                    .put("context", contextTokens.get()),
            )
    }

    fun summaryText(): String = buildString {
        append("本次 run：").append(rounds.get()).append(" 轮，")
        append(buckets.values.sumOf { it.calls.get() }).append(" 次工具调用")
        val failures = buckets.values.sumOf { it.failures.get() }
        if (failures > 0) append("（失败 ").append(failures).append(" 次）")
        append("，耗时合计 ").append(seconds(buckets.values.sumOf { it.totalMs.get() })).append("；")
        append("并发批次 ").append(parallelBatches.get())
            .append("（覆盖 ").append(parallelCalls.get()).append(" 次调用）。\n")
        if (prunedToolResults.get() > 0) {
            append("最近一次请求压成占位的工具结果 ").append(prunedToolResults.get()).append(" 条；")
        }
        if (contextNotices.get() > 0) {
            append("上下文压力提示生效 ").append(contextNotices.get()).append(" 轮；")
        }
        if (prunedToolResults.get() > 0 || contextNotices.get() > 0) append("\n")
        append("token：输入 ").append(inputTokens.get())
            .append("、输出 ").append(outputTokens.get())
            .append("、缓存命中 ").append(cachedTokens.get())
            .append("、思考 ").append(reasoningTokens.get())
            .append("，最近上下文 ").append(contextTokens.get()).append("。")
        val sorted = buckets.entries
            .sortedWith(compareByDescending<Map.Entry<String, Bucket>> { it.value.calls.get() }.thenBy { it.key })
            .take(MAX_LISTED_TOOLS)
        if (sorted.isNotEmpty()) {
            append("\n按调用次数：")
            sorted.forEach { (name, bucket) ->
                append("\n- ").append(name).append(" ×").append(bucket.calls.get())
                if (bucket.failures.get() > 0) append("（失败 ").append(bucket.failures.get()).append(" 次）")
                append("，合计 ").append(seconds(bucket.totalMs.get()))
                    .append("，单次最长 ").append(seconds(bucket.maxMs.get()))
            }
        }
    }

    private fun seconds(millis: Long): String =
        if (millis < 1_000) "${millis}ms" else String.format(java.util.Locale.US, "%.1fs", millis / 1_000.0)

    private companion object {
        const val MAX_LISTED_TOOLS = 8
    }
}
