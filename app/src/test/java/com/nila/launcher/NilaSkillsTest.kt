package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NilaSkillsTest {
    private val skill = NilaSkill("tea", "Tea timer", "tea timer", "set_timer", mapOf("minutes" to "5"))

    @Test fun onlyExactEnabledTriggerMatches() {
        assertEquals(skill, matchingSkill("  Tea Timer! ", listOf(skill)))
        assertNull(matchingSkill("start a tea timer", listOf(skill)))
        assertNull(matchingSkill("tea timer", listOf(skill.copy(enabled = false))))
        assertEquals(skill, matchingSkill("tea timer", listOf(skill, skill.copy(id = "second", capabilityId = "remove_alarm"))))
    }

    @Test fun skillCallUsesRegisteredCapabilityAndTypedArguments() {
        val call = skillCall(skill, NilaEngine().capabilities)
        assertEquals(ToolCall("set_timer", mapOf("minutes" to 5)), call)
    }

    @Test fun invalidOrUnknownCallsAreRejected() {
        val registry = NilaEngine().capabilities
        assertNull(skillCall(skill.copy(arguments = mapOf("minutes" to "abc")), registry))
        assertNull(skillCall(skill.copy(capabilityId = "shell"), registry))
        assertNull(skillCall(skill.copy(capabilityId = "call_contact", arguments = emptyMap()), registry))
        assertNull(skillCall(skill.copy(name = ""), registry))
        assertNull(skillCall(skill.copy(trigger = " "), registry))
    }

    @Test fun skillsRoundTripWithoutExecutingCode() {
        assertEquals(listOf(skill), decodeSkills(encodeSkills(listOf(skill))))
        assertEquals(emptyList<NilaSkill>(), decodeSkills("not-json"))
    }

    @Test fun argumentEditorRejectsAmbiguousInput() {
        assertEquals(mapOf("minutes" to "5", "seconds" to "30"), parseSkillArguments("minutes=5, seconds=30"))
        assertEquals(emptyMap<String, String>(), parseSkillArguments(""))
        assertNull(parseSkillArguments("minutes"))
        assertNull(parseSkillArguments("minutes=5, minutes=6"))
        assertNull(parseSkillArguments("x".repeat(501)))
    }

    @Test fun commitSkillStillRequiresTheEngineConfirmation() {
        val registry = NilaEngine().capabilities
        val call = skillCall(skill.copy(capabilityId = "remove_alarm", arguments = emptyMap()), registry)
        val result = NilaEngine().execute(NilaIntent.Call(call!!))
        org.junit.Assert.assertTrue(result is NilaResponse.NeedsConfirmation)
    }
}
