package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连续同因失败的检测：连续两次才提醒，换了错误或成功就重新计数。 */
class AgentFailureGuardTest {

    @Test
    fun firstFailureDoesNotNudge() {
        val guard = AgentFailureGuard()
        val streak = guard.observe("terminal:EXIT_1:not found")
        assertEquals(1, streak)
        assertFalse(guard.shouldNudge(streak))
    }

    @Test
    fun secondIdenticalFailureNudges() {
        val guard = AgentFailureGuard()
        guard.observe("terminal:EXIT_1:not found")
        val streak = guard.observe("terminal:EXIT_1:not found")
        assertEquals(2, streak)
        assertTrue(guard.shouldNudge(streak))
    }

    @Test
    fun differentFailureRestartsCount() {
        val guard = AgentFailureGuard()
        guard.observe("terminal:EXIT_1:not found")
        val streak = guard.observe("terminal:EXIT_127:command not found")
        assertEquals(1, streak)
        assertFalse(guard.shouldNudge(streak))
    }

    @Test
    fun successResetsStreak() {
        val guard = AgentFailureGuard()
        guard.observe("write_file:PERMISSION_DENIED:denied")
        guard.observe("write_file:PERMISSION_DENIED:denied")
        guard.reset()
        val streak = guard.observe("write_file:PERMISSION_DENIED:denied")
        assertEquals(1, streak)
        assertFalse(guard.shouldNudge(streak))
    }

    @Test
    fun nudgesAgainOnEveryThreshold() {
        val guard = AgentFailureGuard()
        val streaks = (1..5).map { guard.observe("terminal:EXIT_1:boom") }
        assertEquals(listOf(1, 2, 3, 4, 5), streaks)
        // 只提醒 2、4 两次：避免只提醒一次后继续无效重试，也不至于每次都刷。
        assertEquals(listOf(2, 4), streaks.filter { guard.shouldNudge(it) })
    }
}
