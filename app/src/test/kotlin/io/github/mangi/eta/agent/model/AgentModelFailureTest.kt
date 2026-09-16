package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentModelFailureTest {
    @Test
    fun `fields that only affect optimisation can be dropped automatically`() {
        assertEquals(
            "prompt_cache_key",
            failure("UNKNOWN_FIELD", "未知请求字段：prompt_cache_key").droppableField(),
        )
        assertEquals(
            "cache_control",
            failure("UNKNOWN_FIELD", "未知请求字段：cache_control").droppableField(),
        )
        assertEquals(
            "stream_options",
            failure("INVALID_REQUEST", "Unrecognized request argument supplied: stream_options").droppableField(),
        )
        assertEquals(
            "tool_choice",
            failure("MODEL_TOOL_CHOICE_NOT_SUPPORTED", "模型不支持 tool_choice").droppableField(),
        )
        assertEquals(
            "tool_choice",
            failure("UNSUPPORTED_PARAMETER", "Unsupported parameter: tool_choice").droppableField(),
        )
    }

    @Test
    fun `fields that change model behaviour are never dropped automatically`() {
        assertNull(failure("UNKNOWN_FIELD", "未知请求字段：thinking").droppableField())
        assertNull(failure("UNKNOWN_FIELD", "未知请求字段：messages").droppableField())
        assertNull(failure("UNKNOWN_FIELD", "未知请求字段：tools").droppableField())
        assertNull(failure("MODEL_NOT_AVAILABLE", "模型不可用：deepseek-flash").droppableField())
        assertNull(failure("INVALID_REQUEST", "参数无效").droppableField())
        assertNull(failure("RATE_LIMITED", "请求过于频繁").droppableField())
    }

    private fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
}
