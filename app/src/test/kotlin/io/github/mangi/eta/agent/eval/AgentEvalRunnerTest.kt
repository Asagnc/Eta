package io.github.mangi.eta.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEvalRunnerTest {
    private fun task(
        id: String = "t1",
        expectTools: List<String> = listOf("device_status"),
        maxRounds: Int = 8,
    ) = AgentEvalTask(id = id, category = "device", prompt = "看一下电量", expectTools = expectTools, maxRounds = maxRounds)

    private fun raw(
        rounds: Int = 2,
        toolCalls: Int = 2,
        toolFailures: Int = 0,
        usedTools: Set<String> = setOf("device_status"),
        completed: Boolean = true,
        failureCode: String? = null,
    ) = AgentEvalRawOutcome(
        rounds = rounds,
        inputTokens = 100,
        outputTokens = 20,
        toolCalls = toolCalls,
        toolFailures = toolFailures,
        usedTools = usedTools,
        completed = completed,
        elapsedMs = 1_000,
        failureCode = failureCode,
    )

    @Test
    fun `a clean run passes`() {
        val result = AgentEvalRunner({ _, _ -> raw() }).judge(task(), raw())

        assertTrue(result.passed)
        assertEquals(null, result.failureCode)
        assertEquals(2, result.rounds)
    }

    @Test
    fun `an unfinished run does not pass`() {
        val result = AgentEvalRunner({ _, _ -> raw() }).judge(task(), raw(completed = false))

        assertFalse(result.passed)
        assertEquals("EVAL_NOT_COMPLETED", result.failureCode)
    }

    @Test
    fun `a run that never touched the expected tool does not pass`() {
        val result = AgentEvalRunner({ _, _ -> raw() }).judge(task(), raw(usedTools = setOf("search_apps")))

        assertFalse(result.passed)
        assertEquals("EVAL_TOOL_MISS", result.failureCode)
    }

    @Test
    fun `too many rounds does not pass even when the task finished`() {
        val result = AgentEvalRunner({ _, _ -> raw() }).judge(task(maxRounds = 3), raw(rounds = 5))

        assertFalse(result.passed)
        assertEquals("EVAL_TOO_MANY_ROUNDS", result.failureCode)
    }

    @Test
    fun `execution failure wins over the other reasons`() {
        val result = AgentEvalRunner({ _, _ -> raw() }).judge(
            task(),
            raw(rounds = 9, usedTools = emptySet(), completed = false, failureCode = "PROVIDER_ERROR"),
        )

        assertEquals("PROVIDER_ERROR", result.failureCode)
    }

    @Test
    fun `a throwing task becomes a failed result instead of aborting the run`() {
        val runner = AgentEvalRunner(
            { _, _ -> throw IllegalStateException("boom") },
            nowMillis = { 0L },
        )

        val report = runner.run(listOf(task("a"), task("b")), label = "smoke")

        assertEquals(2, report.results.size)
        assertEquals(0, report.passedCount)
        assertTrue(report.results.all { it.failureCode == "EVAL_TASK_THREW" })
    }

    @Test
    fun `report aggregates counts and averages`() {
        val runner = AgentEvalRunner(
            { task, _ -> if (task.id == "a") raw(rounds = 2) else raw(rounds = 4, usedTools = emptySet()) },
            nowMillis = { 0L },
        )

        val report = runner.run(listOf(task("a"), task("b")), label = "mix")

        assertEquals(1, report.passedCount)
        assertEquals(0.5, report.passRate, 0.0001)
        assertEquals(3.0, report.averageRounds, 0.0001)
        assertEquals(200L, report.totalInputTokens)
        assertEquals(4, report.totalToolCalls)
        assertEquals(mapOf("EVAL_TOOL_MISS" to 1), report.failureBreakdown())
    }

    @Test
    fun `report json round-trips through the on-disk form`() {
        val runner = AgentEvalRunner({ _, _ -> raw() }, nowMillis = { 42L })
        val report = runner.run(listOf(task("a")), label = "round-trip")

        val restored = AgentEvalReport.fromJson(report.toJson())

        assertEquals(report.label, restored.label)
        assertEquals(report.results.size, restored.results.size)
        assertEquals(report.passRate, restored.passRate, 0.0001)
        assertEquals(report.averageRounds, restored.averageRounds, 0.0001)
        assertEquals("a", restored.results.single().taskId)
    }

    @Test
    fun `task set parsing keeps usable entries and drops broken ones`() {
        val tasks = AgentEvalTaskSet.parse(
            """
            {"version":1,"tasks":[
              {"id":"a","category":"device","prompt":"看电量","tools":["device_status","top_storage_apps"]},
              {"id":"","category":"x","prompt":"没有 id"},
              {"id":"b","category":"file","prompt":"读文件","tools":[],"max_rounds":3}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, tasks.size)
        assertEquals(listOf("device_status", "top_storage_apps"), tasks[0].expectTools)
        assertEquals(AgentEvalTaskSet.DEFAULT_MAX_ROUNDS, tasks[0].maxRounds)
        assertEquals(3, tasks[1].maxRounds)
        assertTrue(tasks[1].expectTools.isEmpty())
    }

    @Test
    fun `broken task set payload yields no tasks`() {
        assertTrue(AgentEvalTaskSet.parse("not json").isEmpty())
        assertTrue(AgentEvalTaskSet.parse("{}").isEmpty())
    }

    @Test
    fun `metrics parser pulls rounds tokens and tool names out of the snapshot`() {
        val outcome = AgentEvalMetrics.fromStats(
            """{"rounds":3,"tool_calls":5,"tool_failures":1,
                "tokens":{"input":900,"output":120,"cached":30,"reasoning":10,"context":5000},
                "tools":[{"name":"device_status","calls":2,"failed":0},{"name":"read_file","calls":3,"failed":1}]}""",
            elapsedMs = 4_000,
            completed = true,
        )

        assertEquals(3, outcome.rounds)
        assertEquals(900L, outcome.inputTokens)
        assertEquals(120L, outcome.outputTokens)
        assertEquals(5, outcome.toolCalls)
        assertEquals(1, outcome.toolFailures)
        assertEquals(setOf("device_status", "read_file"), outcome.usedTools)
        assertEquals(4_000L, outcome.elapsedMs)
        assertTrue(outcome.completed)
    }

    @Test
    fun `metrics parser flags a missing snapshot instead of reporting an empty run`() {
        val outcome = AgentEvalMetrics.fromStats("not json", elapsedMs = 10, completed = true)

        assertEquals("EVAL_STATS_MISSING", outcome.failureCode)
        assertEquals(0, outcome.toolCalls)
    }
}
