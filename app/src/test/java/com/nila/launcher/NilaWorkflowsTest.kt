package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NilaWorkflowsTest {
    private val registry = NilaEngine().capabilities
    private val workflow = NilaWorkflow(
        "morning", "Morning", "morning routine", listOf(
            NilaWorkflowStep("a", "battery", emptyMap()),
            NilaWorkflowStep("b", "set_timer", mapOf("minutes" to "5"), listOf("a")),
        ),
    )

    @Test fun validatesSequentialTrustedCallsAndKeepsCommitClass() {
        assertEquals(
            listOf(ToolCall("battery", emptyMap()), ToolCall("set_timer", mapOf("minutes" to 5))),
            workflowCalls(workflow, registry),
        )
        val commit = workflow.copy(steps = listOf(NilaWorkflowStep("a", "remove_alarm", emptyMap())))
        assertTrue(registry.getValue("remove_alarm").needsConfirmation)
        assertEquals(1, workflowCalls(commit, registry)?.size)
    }

    @Test fun rejectsCyclicMissingAndForwardDependencies() {
        assertNull(workflowCalls(workflow.copy(steps = listOf(
            workflow.steps[0].copy(dependsOn = listOf("b")), workflow.steps[1].copy(dependsOn = listOf("a")),
        )), registry))
        assertNull(workflowCalls(workflow.copy(steps = listOf(workflow.steps[0].copy(dependsOn = listOf("missing")))), registry))
        assertNull(workflowCalls(workflow.copy(steps = listOf(workflow.steps[0], workflow.steps[1].copy(dependsOn = listOf("b")))), registry))
    }

    @Test fun rejectsUnknownCallsInvalidArgumentsAndOversizedWorkflows() {
        assertNull(workflowCalls(workflow.copy(steps = listOf(NilaWorkflowStep("x", "shell", emptyMap()))), registry))
        assertNull(workflowCalls(workflow.copy(steps = listOf(NilaWorkflowStep("x", "set_timer", mapOf("minutes" to "abc")))), registry))
        assertNull(workflowCalls(workflow.copy(name = "x".repeat(61)), registry))
        assertNull(workflowCalls(workflow.copy(steps = List(MAX_NILA_WORKFLOW_STEPS + 1) { NilaWorkflowStep("s$it", "battery", emptyMap()) }), registry))
    }

    @Test fun dynamicallyDiscoveredMcpToolsAreNotWorkflowSteps() {
        val mcpTool = CapabilityDescriptor("mcp_demo_write", "A discovered MCP tool", emptyList(), PermissionClass.COMMIT, label = "Demo write")
        val withMcp = registry + ("mcp_demo_write" to mcpTool)

        assertNull(workflowCalls(workflow.copy(steps = listOf(NilaWorkflowStep("a", "mcp_demo_write", emptyMap()))), withMcp))
        assertNull(parseWorkflowSteps("mcp_demo_write", withMcp))
        assertNull(parseWorkflowSteps("battery\nmcp_demo_write", withMcp))
    }

    @Test fun encodingRoundTripsAndMalformedDataFailsClosed() {
        assertEquals(listOf(workflow), decodeWorkflows(encodeWorkflows(listOf(workflow))))
        assertTrue(decodeWorkflows("not-json").isEmpty())
        assertFalse(decodeWorkflows(encodeWorkflows(listOf(workflow))).first().steps.isEmpty())
    }

    @Test fun editorBuildsStrictlySequentialSteps() {
        assertEquals(listOf("step1", "step2"), parseWorkflowSteps("battery\nset_timer: minutes=5", registry)?.map { it.id })
        assertEquals(listOf(emptyList<String>(), listOf("step1")), parseWorkflowSteps("battery\nset_timer: minutes=5", registry)?.map { it.dependsOn })
        assertNull(parseWorkflowSteps("battery\nshell", registry))
        assertNull(parseWorkflowSteps("battery\nset_timer: minutes=no", registry))
        assertNull(parseWorkflowSteps("battery\n".repeat(MAX_NILA_WORKFLOW_STEPS + 1), registry))
        assertNull(parseWorkflowSteps("x".repeat(4_001), registry))
        assertTrue(decodeWorkflows("x".repeat(16_385)).isEmpty())
    }

    @Test fun unsupportedProviderAndCommitStepsRequireHandoffOrConfirmation() {
        assertTrue(workflowCanRunWithoutHandoff(registry.getValue("battery")))
        assertFalse(workflowCanRunWithoutHandoff(registry.getValue("search_food")))
        assertFalse(workflowCanRunWithoutHandoff(registry.getValue("remove_alarm")))
    }
}
