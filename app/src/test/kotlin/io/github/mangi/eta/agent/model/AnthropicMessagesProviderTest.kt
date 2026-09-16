package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ProviderTypes
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesProviderTest {
    @Test
    fun thinkingStreamsThroughToolBoundariesAndCompletesBeforeConnectionCloses() {
        val firstDelta = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val serverObservedCompletion = CountDownLatch(1)
        val completedBeforeClose = AtomicBoolean(false)
        val firstChunk = blockStart(0, JSONObject().put("type", "thinking").put("thinking", "开始分析，"))
        val remaining = buildString {
            append(blockDelta(0, JSONObject().put("type", "thinking_delta").put("thinking", "需要工具。")))
            append(blockDelta(0, JSONObject().put("type", "signature_delta").put("signature", "opaque-signature")))
            append(blockStop(0))
            append(blockStop(0))
            append(event("ping", JSONObject()))
            append(blockStart(1, JSONObject().put("type", "tool_use").put("id", "tool_1")
                .put("name", "device_info").put("input", JSONObject())))
            append(blockDelta(1, JSONObject().put("type", "input_json_delta").put("partial_json", "{}")))
            append(blockStop(1))
            append(blockStart(2, JSONObject().put("type", "thinking").put("thinking", "")))
            append(blockDelta(2, JSONObject().put("type", "thinking_delta").put("thinking", "继续分析。")))
            append(blockStop(2))
            append(blockStart(3, JSONObject().put("type", "text").put("text", "最终答案")))
            append(blockStop(3))
            append(event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "tool_use"))))
            append(event("message_stop", JSONObject()))
        }

        withAnthropicServer(
            body = "",
            writeBody = { output ->
                output.write(firstChunk.toByteArray())
                output.flush()
                check(firstDelta.await(5, TimeUnit.SECONDS))
                output.write(remaining.toByteArray())
                output.flush()
                completedBeforeClose.set(completed.await(5, TimeUnit.SECONDS))
                serverObservedCompletion.countDown()
            },
        ) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController()) { event ->
                events += event
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING) {
                    firstDelta.countDown()
                }
                if (event is ProviderEvent.Completed) completed.countDown()
            }

            assertTrue(serverObservedCompletion.await(5, TimeUnit.SECONDS))
            assertTrue(completedBeforeClose.get())
            assertEquals("开始分析，需要工具。继续分析。", response.assistantMessage.getString("reasoning_content"))
            assertEquals("最终答案", response.assistantMessage.getString("content"))
            val ends = events.filterIsInstance<ProviderEvent.BlockEnd>()
            assertEquals(listOf(0, 1, 2, 3), ends.map { it.index })
            assertEquals(listOf("开始分析，需要工具。", "{}", "继续分析。", "最终答案"), ends.map { it.content })
            assertEquals("tool_1", response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals(listOf("开始分析，", "需要工具。", "继续分析。"), events.filterIsInstance<ProviderEvent.BlockDelta>()
                .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta })
        }
    }

    @Test
    fun signatureOnlyAndRedactedThinkingDoNotBecomeVisibleText() {
        val body = buildString {
            append(blockStart(0, JSONObject().put("type", "thinking").put("thinking", "")))
            append(blockDelta(0, JSONObject().put("type", "signature_delta").put("signature", "opaque-signature")))
            append(blockStop(0))
            append(blockStart(1, JSONObject().put("type", "redacted_thinking").put("data", "opaque-data")))
            append(blockStop(1))
            append(blockStart(2, JSONObject().put("type", "text").put("text", "答案")))
            append(blockStop(2))
            append(event("message_stop", JSONObject()))
        }
        withAnthropicServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
            assertEquals("", response.assistantMessage.getString("reasoning_content"))
            assertEquals("答案", response.assistantMessage.getString("content"))
            assertTrue(events.filterIsInstance<ProviderEvent.BlockDelta>().none { it.kind == AssistantBlockKind.THINKING })
        }
    }

    @Test
    fun rejectsUnclosedThinkingBlockEvenWhenMessageStopArrives() {
        val body = blockStart(0, JSONObject().put("type", "thinking").put("thinking", "部分内容")) +
            event("message_stop", JSONObject())
        withAnthropicServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val failure = runCatching {
                AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("内容块未正常结束"))
            assertFalse(events.any { it is ProviderEvent.Completed })
        }
    }

    @Test
    fun thinkingStreamErrorsAndEarlyEofDoNotReportCompletion() {
        val prefix = blockStart(0, JSONObject().put("type", "thinking").put("thinking", "部分思考"))
        val endings = listOf(
            "" to "未正常结束",
            event("error", JSONObject().put("error", JSONObject().put("type", "overloaded_error"))) to "返回错误",
        )
        endings.forEach { (ending, expectedError) ->
            withAnthropicServer(prefix + ending) { baseUrl ->
                val events = mutableListOf<ProviderEvent>()
                val failure = runCatching {
                    AnthropicMessagesProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
                }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains(expectedError))
                assertEquals("部分思考", events.filterIsInstance<ProviderEvent.BlockDelta>().single().delta)
                assertFalse(events.any { it is ProviderEvent.Completed })
            }
        }
    }

    @Test
    fun completeParsesTextAndToolUseStream() {
        val body = buildString {
            append(event("content_block_start", JSONObject()
                .put("type", "content_block_start")
                .put("index", 0)
                .put("content_block", JSONObject().put("type", "text"))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "Hello"))))
            append(event("content_block_stop", JSONObject()
                .put("type", "content_block_stop")
                .put("index", 0)))
            append(event("content_block_start", JSONObject()
                .put("type", "content_block_start")
                .put("index", 1)
                .put("content_block", JSONObject()
                    .put("type", "tool_use")
                    .put("id", "toolu_1")
                    .put("name", "observe_screen")
                    .put("input", JSONObject()))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 1)
                .put("delta", JSONObject()
                    .put("type", "input_json_delta")
                    .put("partial_json", "{\"include_screenshot\":true}"))))
            append(event("content_block_stop", JSONObject()
                .put("type", "content_block_stop")
                .put("index", 1)))
            append(event("message_delta", JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", "tool_use"))
                .put("usage", JSONObject().put("input_tokens", 4).put("output_tokens", 2))))
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system"
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray().put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", "observe_screen")
                                    .put("description", "observe")
                                    .put("parameters", JSONObject().put("type", "object"))
                            )
                    )
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            val toolCall = response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("toolu_1", toolCall.getString("id"))
            assertEquals("observe_screen", toolCall.getJSONObject("function").getString("name"))
            assertEquals(
                "{\"include_screenshot\":true}",
                toolCall.getJSONObject("function").getString("arguments")
            )
            assertTrue(requestBody.get().contains("\"tools\""))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.TEXT }
                    .joinToString("") { it.delta }
            )
            assertEquals(
                listOf(
                    "start:TEXT:0",
                    "delta:TEXT:0:Hello",
                    "end:TEXT:0",
                    "start:TOOL_CALL:1",
                    "delta:TOOL_CALL:1:{\"include_screenshot\":true}",
                    "end:TOOL_CALL:1",
                ),
                events.mapNotNull { event ->
                    when (event) {
                        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
                        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
                        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
                        else -> null
                    }
                },
            )
            assertEquals(1, events.filterIsInstance<ProviderEvent.Usage>().size)
        }
    }

    @Test
    fun completeBuildsAdaptiveThinkingRequestWhenEnabled() {
        val body = buildString {
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        providerSourceType = "anthropic",
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system",
                        thinkingEnabled = true,
                        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.MEDIUM,
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
            )

            val request = JSONObject(requestBody.get())
            assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
            assertEquals("medium", request.getJSONObject("output_config").getString("effort"))
        }
    }

    @Test
    fun consecutiveToolResultsShareOneUserMessage() {
        val body = event("message_stop", JSONObject().put("type", "message_stop"))
        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system",
                    ),
                    messages = JSONArray()
                        .put(JSONObject().put("role", "user").put("content", "跑两个命令"))
                        .put(
                            JSONObject()
                                .put("role", "assistant")
                                .put(
                                    "tool_calls",
                                    JSONArray()
                                        .put(toolCallJson("call_1"))
                                        .put(toolCallJson("call_2"))
                                )
                        )
                        .put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "one"))
                        .put(JSONObject().put("role", "tool").put("tool_call_id", "call_2").put("content", "two")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
            )

            val messages = JSONObject(requestBody.get()).getJSONArray("messages")
            assertEquals(3, messages.length())
            val toolResults = messages.getJSONObject(2)
            assertEquals("user", toolResults.getString("role"))
            val blocks = toolResults.getJSONArray("content")
            assertEquals(2, blocks.length())
            assertEquals("call_1", blocks.getJSONObject(0).getString("tool_use_id"))
            assertEquals("call_2", blocks.getJSONObject(1).getString("tool_use_id"))
        }
    }

    @Test
    fun promptCacheAbsentUnlessProviderEnablesIt() {
        val requestBody = AtomicReference<String>()
        withAnthropicServer(event("message_stop", JSONObject()), onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
            )

            assertFalse(JSONObject(requestBody.get()).has("cache_control"))
        }
    }

    @Test
    fun promptCacheEnabledAddsTopLevelEphemeralBreakpoint() {
        val requestBody = AtomicReference<String>()
        withAnthropicServer(event("message_stop", JSONObject()), onRequest = requestBody::set) { baseUrl ->
            val request = providerRequest(baseUrl)
            AnthropicMessagesProvider.complete(
                request = request.copy(config = request.config.copy(promptCacheEnabled = true)),
                runController = AgentRunController(),
            )

            assertEquals(
                "ephemeral",
                JSONObject(requestBody.get()).getJSONObject("cache_control").getString("type"),
            )
        }
    }

    @Test
    fun contextEditingAbsentUnlessProviderEnablesIt() {
        val requestBody = AtomicReference<String>()
        val requestHeaders = AtomicReference<Map<String, String>>()
        withAnthropicServer(
            body = event("message_stop", JSONObject()),
            onRequest = requestBody::set,
            onHeaders = requestHeaders::set,
        ) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
            )

            assertFalse(requestHeaders.get().containsKey("anthropic-beta"))
            assertFalse(JSONObject(requestBody.get()).has("context_management"))
        }
    }

    @Test
    fun contextEditingAddsBetaHeaderAndToolResultStrategy() {
        val requestBody = AtomicReference<String>()
        val requestHeaders = AtomicReference<Map<String, String>>()
        withAnthropicServer(
            body = event("message_stop", JSONObject()),
            onRequest = requestBody::set,
            onHeaders = requestHeaders::set,
        ) { baseUrl ->
            val request = providerRequest(baseUrl)
            AnthropicMessagesProvider.complete(
                request = request.copy(config = request.config.copy(contextEditingEnabled = true)),
                runController = AgentRunController(),
            )

            assertEquals("context-management-2025-06-27", requestHeaders.get()["anthropic-beta"])
            assertEquals(
                "clear_tool_uses_20250919",
                JSONObject(requestBody.get())
                    .getJSONObject("context_management")
                    .getJSONArray("edits")
                    .getJSONObject(0)
                    .getString("type"),
            )
        }
    }

    @Test
    fun contextEditingMergesCustomAnthropicBetaHeader() {
        val requestHeaders = AtomicReference<Map<String, String>>()
        withAnthropicServer(
            body = event("message_stop", JSONObject()),
            onHeaders = requestHeaders::set,
        ) { baseUrl ->
            val request = providerRequest(baseUrl)
            AnthropicMessagesProvider.complete(
                request = request.copy(
                    config = request.config.copy(
                        contextEditingEnabled = true,
                        customHeaders = listOf(CustomHeader("anthropic-beta", "prompt-caching-2024-07-31")),
                    ),
                ),
                runController = AgentRunController(),
            )

            assertEquals(
                "prompt-caching-2024-07-31,context-management-2025-06-27",
                requestHeaders.get()["anthropic-beta"],
            )
        }
    }

    private fun toolCallJson(id: String) =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", "terminal").put("arguments", "{\"action\":\"open\"}")
            )

    private fun providerRequest(baseUrl: String) = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            providerType = ProviderTypes.ANTHROPIC,
            baseUrl = baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        ),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "测试推理")),
        tools = JSONArray(),
    )

    private fun blockStart(index: Int, content: JSONObject) =
        event("content_block_start", JSONObject().put("index", index).put("content_block", content))

    private fun blockDelta(index: Int, delta: JSONObject) =
        event("content_block_delta", JSONObject().put("index", index).put("delta", delta))

    private fun blockStop(index: Int) = event("content_block_stop", JSONObject().put("index", index))

    private fun event(name: String, data: JSONObject): String =
        "event: $name\ndata: ${JSONObject(data.toString()).put("type", name)}\n\n"

    private fun withAnthropicServer(
        body: String,
        onRequest: (String) -> Unit = {},
        onHeaders: (Map<String, String>) -> Unit = {},
        writeBody: ((OutputStream) -> Unit)? = null,
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/messages") { exchange ->
            onHeaders(
                exchange.requestHeaders.entries.associate { entry ->
                    entry.key.lowercase() to entry.value.joinToString(",")
                }
            )
            onRequest(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, if (writeBody == null) bytes.size.toLong() else 0)
            exchange.responseBody.use { output ->
                if (writeBody == null) output.write(bytes) else writeBody(output)
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
