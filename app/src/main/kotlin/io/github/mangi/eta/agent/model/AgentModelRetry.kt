package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var activeRequest = request
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val response = provider.complete(activeRequest, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                if (response.isUnexplainedEmptyCompletion()) {
                    // 上游声称正常结束却什么都没给：这多是上游或网关抖动，按瞬时失败重试，
                    // 而不是让整次运行直接失败。长度截断、内容过滤这类有明确原因的空态不在此列。
                    throw AgentModelFailure(
                        code = "EMPTY_COMPLETION",
                        retryable = true,
                        message = "模型接口本轮返回空响应（finish_reason=" +
                            response.assistantMessage.optString("finish_reason") +
                            "），既没有正文也没有工具调用。",
                    )
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified, recoveryAllowed = false,
                )
                val droppable = classified.droppableField()
                if (droppable != null && droppable !in activeRequest.dropFields) {
                    // 上游点名拒收某个语义冗余的字段：去掉它再试一次，不占用瞬时错误的重试预算。
                    onEvent(AgentEvent.ModelRetryScheduled(round, retries + 1, MAX_RETRIES, 0, classified.code))
                    activeRequest = activeRequest.copy(dropFields = activeRequest.dropFields + droppable)
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    /**
     * 只有"上游申报正常结束（END_TURN）、却既无正文也无工具调用"才算是不可解释的空响应。
     * 其余空态（长度截断、内容过滤、未完成）都带有明确原因，重试不会改变结果。
     */
    private fun ProviderResponse.isUnexplainedEmptyCompletion(): Boolean {
        if (stopReason != AssistantStopReason.END_TURN) return false
        val calls = assistantMessage.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) return false
        val content = assistantMessage.optString("content").trim()
        return content.isEmpty() || content == "null"
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val BASE_DELAY_MS = 2_000L
    }
}
