package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationRunPurgeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun purgeRemovesEveryRunOfTheDeletedConversationAndKeepsOthers() {
        addRun(conversationId = "conv-a", runId = "run-a1")
        addRun(conversationId = "conv-a", runId = "run-a2")
        addRun(conversationId = "conv-b", runId = "run-b1")

        assertEquals(6, ConversationRunPurge.purge(context, "conv-a"))

        assertEquals(listOf("run-b1"), AgentRunArchiveStore.list(context).map { it.result.runId })
        assertEquals(listOf("run-b1"), AgentRunCheckpointStore.list(context).map { it.runId })
        assertEquals(listOf("run-b1"), AgentRuntimeResultStore.list(context).map { it.result.runId })
    }

    @Test
    fun purgeMatchesLegacyPlainPayloadAndIgnoresUnknownConversation() {
        addRun(conversationId = "conv-a", runId = "run-a1", encodePayload = false)
        addRun(conversationId = "conv-b", runId = "run-b1", encodePayload = false)

        assertEquals(0, ConversationRunPurge.purge(context, "conv-c"))
        assertEquals(3, ConversationRunPurge.purge(context, "conv-a"))

        assertEquals(listOf("run-b1"), AgentRuntimeResultStore.list(context).map { it.result.runId })
        assertEquals(0, ConversationRunPurge.purge(context, "   "))
    }

    private fun addRun(conversationId: String, runId: String, encodePayload: Boolean = true) {
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = if (encodePayload) {
                AgentUiHandoffPayload(conversationId = conversationId).toJson()
            } else {
                conversationId
            },
        )
        val result = AgentRuntimeWire.RunResult(runId = runId, ok = true, content = "ok")
        val request = AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "prompt",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "",
                model = "model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = handoff,
        )
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(handoff, emptyList(), result, 1L))
        AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff, result, 1L))
    }
}
