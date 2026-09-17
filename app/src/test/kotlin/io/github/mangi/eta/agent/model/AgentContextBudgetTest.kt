package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetTest {

    private val tools = JSONArray()

    private fun messagesOf(content: String) =
        JSONArray().put(JSONObject().put("role", "user").put("content", content))

    @Test
    fun `cjk heavy estimate is corrected downward by real usage`() {
        val budget = AgentContextBudget(window = 1_000_000)
        val messages = messagesOf("测试内容".repeat(500))
        val before = budget.estimate(messages, tools)
        assertTrue("raw estimate should count CJK chars", before >= 2_000)

        // 服务端只报 1000：字符估算高估一倍，校准必须能向下修正而不是被夹在 1.0。
        budget.observe(AgentTokenUsage(inputTokens = 1_000), before)

        val after = budget.estimate(messages, tools)
        assertTrue("calibration should shrink the inflated estimate", after < before)
        assertTrue(after <= 1_100)
    }

    @Test
    fun `estimate is never below the last real input`() {
        val budget = AgentContextBudget(1_000_000)
        budget.observe(AgentTokenUsage(inputTokens = 50_000), 10_000)
        assertTrue(budget.estimate(messagesOf("hi"), tools) >= 50_000)
    }

    @Test
    fun `compaction waits until ninety percent of the window`() {
        val budget = AgentContextBudget(100_000)
        assertFalse(budget.shouldCompact(89_999))
        assertTrue(budget.shouldCompact(90_000))
        assertTrue(budget.exceedsWindow(100_000))
    }

    @Test
    fun `unknown window neither notices nor compacts`() {
        val budget = AgentContextBudget(null)
        assertFalse(budget.shouldCompact(10_000_000))
        assertEquals(null, budget.windowTokens)
    }
}
