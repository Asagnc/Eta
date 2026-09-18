package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextPrunerTest {
    @Test
    fun onlyTheMostRecentToolResultsKeepTheirFullContent() {
        val messages = conversation(toolResults = 3)
        val originalLastTool = messages.getJSONObject(7).getString("content")

        val pruned = AgentContextPruner.prune(messages, keepRecentToolResults = 1)

        assertEquals(2, pruned)
        assertEquals(AgentContextPruner.placeholder(), messages.getJSONObject(3).getString("content"))
        assertEquals(AgentContextPruner.placeholder(), messages.getJSONObject(5).getString("content"))
        assertEquals(originalLastTool, messages.getJSONObject(7).getString("content"))
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user 输入", messages.getJSONObject(1).getString("content"))
        assertTrue(messages.getJSONObject(2).has("tool_calls"))
    }

    @Test
    fun shortResultsAndDisabledPruningAreLeftAlone() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "tool").put("content", """{"ok":true}"""))
            .put(JSONObject().put("role", "tool").put("content", "x".repeat(500)))
        assertEquals(0, AgentContextPruner.prune(messages, keepRecentToolResults = 1))
        assertEquals("""{"ok":true}""", messages.getJSONObject(0).getString("content"))

        val second = JSONArray().put(JSONObject().put("role", "tool").put("content", "x".repeat(500)))
        assertEquals(0, AgentContextPruner.prune(second, keepRecentToolResults = -1))
        assertEquals("x".repeat(500), second.getJSONObject(0).getString("content"))
    }

    @Test
    fun copyIsIndependentFromTheStoredHistory() {
        val messages = conversation(toolResults = 3)
        val copy = AgentContextPruner.copyOf(messages)
        AgentContextPruner.prune(copy, keepRecentToolResults = 0)

        assertEquals(AgentContextPruner.placeholder(), copy.getJSONObject(3).getString("content"))
        assertEquals(0, AgentContextPruner.prune(messages, keepRecentToolResults = 99))
        assertTrue(messages.getJSONObject(3).getString("content").startsWith("工具结果 1"))
        assertFalse(messages.toString().contains("pruned"))
    }

    private fun conversation(toolResults: Int): JSONArray = JSONArray().also { messages ->
        messages
            .put(JSONObject().put("role", "system").put("content", "系统提示"))
            .put(JSONObject().put("role", "user").put("content", "user 输入"))
        repeat(toolResults) { index ->
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-$index"))),
            )
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call-$index")
                    // 保护区按 token 划分（最近 40k token 内的工具结果不裁剪），
                    // 内容要足够大才能落到保护区之外，否则一条也裁不掉。
                    .put("content", "工具结果 ${index + 1}：" + "y".repeat(150_000)),
            )
        }
    }
}
