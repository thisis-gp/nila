package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillActionsTest {
    private val water = listOf("water" to "Water", "reading-log" to "Reading Log")
    private val engine = NilaEngine(installedApps = { water })
    private fun tool(text: String, on: NilaEngine = engine) = (on.route(text) as? NilaIntent.Call)?.call

    @Test
    fun ideasForAnAppGoToTheAppBuilder() {
        for (idea in listOf("build me an expense tracker", "I want an expense tracker that reads my bank sms", "make me an app to count my water", "can you create a packing checklist")) {
            assertEquals(idea, "build_app", tool(idea)?.capabilityId)
        }
        assertEquals("I want an expense tracker that reads my bank sms", tool("I want an expense tracker that reads my bank sms")?.args?.get("idea"))
    }

    @Test
    fun notesAreStillNotes() {
        assertEquals("add_note", tool("make a note buy milk")?.capabilityId)
    }

    @Test
    fun openingABuiltAppWinsOverPhoneApps() {
        assertEquals(ToolCall("open_built_app", mapOf("app" to "reading-log")), tool("open reading log"))
        assertEquals(OpenMiniApp("water"), engine.respond("open water"))
        assertNotEquals("open_built_app", tool("open water", NilaEngine())?.capabilityId)
    }

    @Test
    fun builtAppsCanBeListed() {
        assertEquals(ShowMiniApps, engine.respond("show my apps"))
    }

    @Test
    fun removingABuiltAppAsksFirst() {
        assertEquals(ToolCall("remove_built_app", mapOf("app" to "water")), tool("delete the water app"))
        assertTrue(engine.respond("delete the water app") is NilaResponse.NeedsConfirmation)
    }
}
