package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterToolsTest {
    private val registry = NilaEngine().capabilities

    @Test fun namesTheToolTheInputMentions() {
        val tools = routerTools("order chicken biryani from Meghana Foods", registry)
        assertTrue("search_food must be offered: ${tools.map { it.id }}", tools.any { it.id == "search_food" })
        assertTrue("chat must always be offered", tools.any { it.id == CHAT_TOOL.id })
    }

    @Test fun keepsTheParaphraseToolsWhenNothingMatches() {
        // "I'm starving" names no tool; the everyday paraphrase set still reaches the model.
        val tools = routerTools("I'm starving", registry)
        assertTrue(tools.any { it.id == "search_food" })
        assertTrue(tools.any { it.id == "search_groceries" })
        assertTrue(tools.any { it.id == CHAT_TOOL.id })
    }

    @Test fun neverExceedsTheBudget() {
        // Every word of every description matches: the shortlist still stays bounded.
        val everything = registry.values.joinToString(" ") { it.id.replace('_', ' ') + " " + it.description }
        val tools = routerTools(everything, registry)
        assertTrue("shortlist of ${tools.size} tools", tools.size <= MAX_ROUTER_TOOLS)
    }

    @Test fun aTimerPromptKeepsTheTimerTools() {
        val tools = routerTools("pause the running timer", registry)
        assertTrue(tools.any { it.id == "timer_control" })
        assertTrue(tools.any { it.id == "set_timer" })
    }

    @Test fun everyOfferedToolIsRegistered() {
        val tools = routerTools("remind me to pay the electricity bill every month and call Amma", registry)
        assertTrue(tools.isNotEmpty())
        // chat is the model-side escape hatch, not a registry capability; everything else must be real.
        tools.filter { it.id != CHAT_TOOL.id }.forEach { assertEquals(it, registry.getValue(it.id)) }
    }
}
