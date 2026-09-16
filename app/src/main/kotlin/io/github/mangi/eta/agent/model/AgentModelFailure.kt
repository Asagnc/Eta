package io.github.mangi.eta.agent.model

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.Locale
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val recoveryAllowed: Boolean = true,
) : IllegalStateException(message, cause) {
    /**
     * 上游点名拒收、且省略后不改变模型行为的字段。只认这些：省略它们只影响优化或统计，
     * 不会改变推理深度、工具集合或用户自定义内容，所以可以自动重试；其它字段一律交回用户处理。
     */
    fun droppableField(): String? {
        val text = message.orEmpty()
        if (code.uppercase(Locale.ROOT) in TOOL_CHOICE_CODES) return "tool_choice"
        val named = REJECTED_FIELD_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)
        } ?: return null
        val normalized = named.lowercase(Locale.ROOT)
        return normalized.takeIf { it in DROPPABLE_FIELDS }
    }

    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val TOOL_CHOICE_CODES = setOf(
            "TOOL_CHOICE_NOT_SUPPORTED",
            "MODEL_TOOL_CHOICE_NOT_SUPPORTED",
        )
        private val DROPPABLE_FIELDS = setOf(
            "tool_choice",
            "stream_options",
            "parallel_tool_calls",
            "prompt_cache_key",
            // 提示缓存只影响计费与延迟，去掉不改变模型行为。
            "cache_control",
        )
        private val REJECTED_FIELD_PATTERNS = listOf(
            Regex("""未知(?:请求)?字段[：:\s]+([A-Za-z0-9_.-]+)"""),
            Regex("""不支持(?:的)?(?:参数|字段)[：:\s]+([A-Za-z0-9_.-]+)"""),
            Regex("""unknown field[：:\s]+["']?([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE),
            Regex("""unrecognized (?:request )?(?:argument|field)[^:：]*[：:]\s*["']?([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE),
            Regex("""unsupported (?:parameter|field)[^:：]*[：:]\s*["']?([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE),
        )
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
            // 网关路由层可能暂时无法把请求送到模型，属可恢复抖动。
            "model_not_available",
        )

        /**
         * 只展示已解析出的错误对象内容：正文不是 JSON 时保持沉默，避免把网关的
         * 挑战页、代理错误页之类正文当作服务端说明展示。message 为空时退回 code，
         * 部分网关只回一个错误码。
         */
        private fun serverDetail(error: JSONObject?): String {
            val detail = error?.optString("message").orEmpty().trim()
                .ifBlank { error?.optString("code").orEmpty().trim() }
            if (detail.isBlank()) return ""
            return "｜服务端返回：" + detail.take(400)
        }

        /** 兼容 `{"error":{...}}` 与把 code/message 直接放在顶层的网关。 */
        private fun errorPayload(body: String): JSONObject? = try {
            JSONObject(body).let { payload -> payload.optJSONObject("error") ?: payload }
        } catch (_: org.json.JSONException) {
            null
        }

        fun http(status: Int, body: String): AgentModelFailure {
            val error = errorPayload(body)
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val permanent = isPermanent(error, body)
            val summary = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
            else when (status) {
                400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                429 -> "模型接口暂时限流（HTTP 429）。"
                else -> "模型接口返回 HTTP $status"
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = (status in transientStatus && !permanent) ||
                    // 部分网关用 4xx 的非标准 code 表示瞬时不可用（如路由摘走模型）。
                    !permanent && error?.optString("code").orEmpty() in transientCodes,
                // 服务端原文常常直接点明请求哪里不合法，只保留概括文案会让排查没有线索。
                message = summary + serverDetail(error),
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
