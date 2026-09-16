package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentRunnerTest {
    @Test
    fun subAgentSeesOnlyReadOnlyToolsAndForbiddenCallsAreReportedBack() {
        val provider = FakeProvider(
            mutableListOf(
                toolCallMessage("terminal", """{"action":"open_and_exec","command":"rm -rf /"}"""),
                finalMessage("结论：并发上限常量在 AgentLoop 里。"),
            ),
        )
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())

        val outcome = runner(provider, events).run(
            AgentSubAgentRunner.Request(role = "检索", brief = "找出并发上限常量"),
        )

        val offered = toolNames(provider.requests.first().tools)
        assertEquals(setOf("read_file", "search_code", "list_directory"), offered)
        val followUp = provider.requests.last().messages.toString()
        assertTrue("被禁用的工具调用要作为工具结果回写", followUp.contains("SUB_AGENT_TOOL_FORBIDDEN"))
        assertTrue(outcome.ok)
        assertTrue(outcome.summary.contains("并发上限常量"))
        assertEquals(
            listOf("started", "finished"),
            events.filterIsInstance<AgentEvent.SubAgentUpdated>().map { it.phase },
        )
    }

    @Test
    fun tokenBudgetStopsTheSubAgentBeforeAnyModelRequest() {
        val provider = FakeProvider(mutableListOf(finalMessage("不该被调用")))
        val outcome = runner(provider, mutableListOf(), tokenBudget = 1).run(
            AgentSubAgentRunner.Request(role = "检索", brief = "任意任务"),
        )

        assertFalse(outcome.ok)
        assertEquals("SUB_AGENT_BUDGET_EXCEEDED", outcome.errorCode)
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun rolesRunInIsolatedContextsAndFailuresDoNotBreakOtherRoles() {
        val provider = object : FakeProvider(mutableListOf()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request
                if (request.sessionId.contains("攻击")) throw IllegalStateException("boom")
                val role = request.messages.getJSONObject(0).getString("content")
                return ProviderResponse(finalMessage("摘要：$role"))
            }
        }

        val outcomes = runner(provider, mutableListOf()).runAll(
            listOf(
                AgentSubAgentRunner.Request(role = "攻击视角", brief = "评估这个漏洞"),
                AgentSubAgentRunner.Request(role = "防御视角", brief = "评估这个漏洞"),
            ),
        )

        assertEquals(listOf("攻击视角", "防御视角"), outcomes.map { it.role })
        assertFalse(outcomes[0].ok)
        assertEquals("SUB_AGENT_ERROR", outcomes[0].errorCode)
        assertTrue(outcomes[1].ok)
        assertTrue("防御视角" in outcomes[1].summary)
        val firstMessageOfDefence = provider.requests
            .first { it.sessionId.contains("防御") }
            .messages.getJSONObject(0).getString("content")
        assertFalse("角色之间不能互相看到对方的提示", firstMessageOfDefence.contains("攻击视角"))
    }

    private fun runner(
        provider: AgentProviderClient,
        events: MutableList<AgentEvent>,
        tokenBudget: Int = 30_000,
    ): AgentSubAgentRunner = AgentSubAgentRunner(
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "k",
            model = "m",
            systemPrompt = "",
        ),
        provider = provider,
        runController = AgentRunController(),
        onEvent = { event -> events += event },
        parentTools = parentTools(),
        toolExecutorFor = { allowed ->
            AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", allowed.contains(call.name))
                        .put("code", if (allowed.contains(call.name)) "OK" else "SUB_AGENT_TOOL_FORBIDDEN")
                        .put("message", "子智能体不能使用 ${call.name}")
                        .toString(),
                )
            }
        },
        tokenBudget = tokenBudget,
    )
}

private open class FakeProvider(
    private val responses: MutableList<JSONObject>,
) : AgentProviderClient {
    override val id: String = "fake"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = false,
        streamingToolCalls = false,
        imageInput = false,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = false,
    )
    val requests = mutableListOf<ProviderRequest>()

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        requests += request
        return ProviderResponse(responses.removeFirstOrNull() ?: finalMessage("默认结束"))
    }
}

private fun parentTools(): JSONArray = JSONArray()
    .put(tool("read_file"))
    .put(tool("search_code"))
    .put(tool("list_directory"))
    .put(tool("terminal"))

private fun tool(name: String): JSONObject = AgentToolSchema.function(
    name = name,
    description = name,
    parameters = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray()),
)

private fun toolNames(tools: JSONArray): Set<String> = (0 until tools.length())
    .mapTo(linkedSetOf()) { index ->
        tools.getJSONObject(index).getJSONObject("function").getString("name")
    }

private fun toolCallMessage(name: String, arguments: String): JSONObject = JSONObject()
    .put("role", "assistant")
    .put("content", "")
    .put("finish_reason", "tool_calls")
    .put(
        "tool_calls",
        JSONArray().put(
            JSONObject()
                .put("id", "call-1")
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", arguments)),
        ),
    )

private fun finalMessage(text: String): JSONObject = JSONObject()
    .put("role", "assistant")
    .put("content", text)
    .put("finish_reason", "stop")
