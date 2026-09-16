package io.github.mangi.eta.agent.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** ask 档的判定规则：只读永远放行，写/执行/设备动作按档位处理。 */
class AgentRuntimePolicyPermissionTest {

    @Test
    fun readToolsAlwaysAllowed() {
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(0, "read_file"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(1, "search_code"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(2, "list_directory"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(2, "run_stats"))
    }

    @Test
    fun askModeAsksForWriteExecuteAndDeviceTools() {
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, "write_file"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, "edit_file"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, "run_command"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, "terminal"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, "tap_element"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, "set_volume"))
    }

    @Test
    fun allowModeKeepsEverythingOpen() {
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(0, "write_file"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(0, "run_command"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(0, "tap_element"))
    }

    @Test
    fun denyModeBlocksHighRiskToolsButKeepsReads() {
        assertEquals(AgentRuntimePolicy.ToolPermission.DENY, AgentRuntimePolicy.permissionFor(2, "edit_file"))
        assertEquals(AgentRuntimePolicy.ToolPermission.DENY, AgentRuntimePolicy.permissionFor(2, "run_command"))
        assertEquals(AgentRuntimePolicy.ToolPermission.DENY, AgentRuntimePolicy.permissionFor(2, "set_volume"))
        assertEquals(AgentRuntimePolicy.ToolPermission.ALLOW, AgentRuntimePolicy.permissionFor(2, "read_file"))
    }

    @Test
    fun groupKeySeparatesPathsAndFallsBackToToolName() {
        val withPath = JSONObject().put("path", "/data/local/tmp/eta/log.txt")
        assertEquals(
            "write_file:/data/local/tmp/eta/log.txt",
            AgentRuntimePolicy.approvalGroupKey("write_file", withPath),
        )
        assertEquals("run_command", AgentRuntimePolicy.approvalGroupKey("run_command", JSONObject()))
        assertEquals("run_command", AgentRuntimePolicy.approvalGroupKey("run_command", null))
    }

    @Test
    fun toolNameCaseAndSpacesDoNotEscapeClassification() {
        assertEquals(AgentRuntimePolicy.ToolPermission.ASK, AgentRuntimePolicy.permissionFor(1, " Write_File "))
        assertEquals(AgentRuntimePolicy.ToolRisk.EXECUTE, AgentRuntimePolicy.riskOf("RUN_COMMAND"))
    }
}
