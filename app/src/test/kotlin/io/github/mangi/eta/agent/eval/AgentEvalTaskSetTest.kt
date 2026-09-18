package io.github.mangi.eta.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务集分层的守护：light 是日常回归用的快速层，必须真的存在、够小，且每个任务都标了层。
 * 少一个 tier 字段或把任务全标成 full，工具页的「运行快速评估」就会静默退化成跑全量——
 * 那正是之前评测既慢又贵的原因，所以这里用测试把边界钉住。
 */
class AgentEvalTaskSetTest {

    @Test
    fun `built-in tasks all declare a tier and stay unique`() {
        val tasks = AgentEvalTaskSet.BUILT_IN
        assertTrue("内置任务集不应为空", tasks.isNotEmpty())
        assertEquals("任务 id 必须唯一", tasks.size, tasks.map { it.id }.distinct().size)
        tasks.forEach { task ->
            assertTrue(
                "任务 ${task.id} 的 tier 非法：${task.tier}",
                task.tier == AgentEvalTask.TIER_LIGHT || task.tier == AgentEvalTask.TIER_FULL,
            )
        }
    }

    @Test
    fun `light tier exists and stays small`() {
        val all = AgentEvalTaskSet.BUILT_IN
        val light = all.filter { it.tier == AgentEvalTask.TIER_LIGHT }
        assertTrue("至少要有几个轻量任务，否则快速评估没有意义", light.size >= 3)
        assertTrue("light 层是 2–3 轮能过的用例，控制在 8 个以内", light.size <= 8)
        assertTrue("light 层必须是全量的真子集", light.size < all.size)
    }

    @Test
    fun `parse reads tier and defaults to full`() {
        val json = """
            {"tasks":[
              {"id":"a","category":"c","prompt":"p","tier":"light"},
              {"id":"b","category":"c","prompt":"p"}
            ]}
        """.trimIndent()
        val parsed = AgentEvalTaskSet.parse(json)
        assertEquals(2, parsed.size)
        assertEquals(AgentEvalTask.TIER_LIGHT, parsed[0].tier)
        assertEquals(AgentEvalTask.TIER_FULL, parsed[1].tier)
    }
}
