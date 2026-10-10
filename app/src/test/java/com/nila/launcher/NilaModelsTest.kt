package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NilaModelsTest {
    private val engine = NilaEngine()
    private val alarm = engine.capabilities.getValue("create_alarm")

    @Test
    fun toolSchemaListsTypedParamsAndRequiredOnes() {
        val schema = toolSchemaJson(alarm)
        assertTrue(schema.startsWith("{\"name\":\"create_alarm\""))
        assertTrue("\"hour\":{\"type\":\"integer\"" in schema)
        assertTrue("\"meridiem\":{\"type\":\"string\"" in schema)
        assertTrue("\"required\":[\"hour\"]" in schema)
    }

    @Test
    fun toolSchemaEscapesQuotes() {
        val tricky = CapabilityDescriptor("say", "Say \"hi\"\nnow", listOf(Param("text", ParamType.STRING)), PermissionClass.READ)
        assertTrue("\"description\":\"Say \\\"hi\\\"\\nnow\"" in toolSchemaJson(tricky))
    }

    @Test
    fun modelArgumentsBecomeCheckableTypes() {
        // Models often emit JSON numbers as doubles or strings.
        val args = coerceArgs(mapOf("hour" to 6.0, "minute" to "30", "meridiem" to "pm", "unused" to null), alarm)
        assertEquals(mapOf("hour" to 6, "minute" to 30, "meridiem" to "pm"), args)
        assertTrue(checkCall(ToolCall("create_alarm", args), engine.capabilities) is CallCheck.Ok)
    }

    @Test
    fun lossyNumbersAreLeftForCheckCallToReject() {
        val args = coerceArgs(mapOf("hour" to 6.5, "minute" to 0), alarm)
        assertEquals(6.5, args["hour"])
        assertTrue(checkCall(ToolCall("create_alarm", args), engine.capabilities) is CallCheck.Rejected)
    }

    @Test
    fun inventedNumbersAreRejected() {
        // Seen on the iQOO 15: "remind me at 9 pm" became create_alarm(hour = 10).
        assertFalse(numbersGrounded(mapOf("hour" to 10), "remind me at 9 pm"))
        assertTrue(numbersGrounded(mapOf("hour" to 9, "minute" to 0, "meridiem" to "pm"), "remind me at 9 pm"))
        assertTrue(numbersGrounded(mapOf("hour" to 5, "minute" to 45), "wake me up at 5:45 tomorrow"))
        assertTrue(numbersGrounded(mapOf("query" to "biryani"), "find biryani"))
    }

    @Test
    fun alarmWithoutMinuteMeansOnTheHour() {
        assertEquals(NilaResponse.Alarm(21, 0), engine.execute(NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 9, "meridiem" to "pm")))))
    }

    @Test
    fun alarmTimeComesFromTheUsersWords() {
        // FunctionGemma proposed hour = 9 (no pm) and hour = 5 (no :45) for these on the iQOO 15.
        assertEquals(mapOf("hour" to 9, "minute" to 0, "meridiem" to "pm"), engine.alarmArgs("remind me at 9 pm"))
        assertEquals(mapOf("hour" to 5, "minute" to 45), engine.alarmArgs("wake me up at 5:45 tomorrow"))
        assertEquals(null, engine.alarmArgs("wake me up early"))
    }

    @Test
    fun greetingsAndQuestionsAboutNilaSkipTools() {
        listOf("hi", "Hello there", "who are you", "What can you do?", "what's your name", "thanks!").forEach {
            assertTrue(it, engine.isSmallTalk(it))
        }
        listOf("i'm hungry", "wake me up at 6", "high protein dinner", "open youtube").forEach {
            assertFalse(it, engine.isSmallTalk(it))
        }
    }

    @Test
    fun alarmTimeReadsCommonWritingStyles() {
        // On the iQOO 15 FunctionGemma turned "1:30" into hour = 130 and nothing could read it.
        val oneThirty = mapOf("hour" to 1, "minute" to 30)
        listOf("put an alarm on 1:30", "alarm at 1.30", "wake me at 1 30", "alarm at 130").forEach {
            assertEquals(it, oneThirty, engine.alarmArgs(it))
        }
        assertEquals(mapOf("hour" to 13, "minute" to 30), engine.alarmArgs("alarm at 1330"))
    }

    @Test
    fun alarmWithoutAmOrPmPicksTheNextOne() {
        val call = NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 1, "minute" to 30)))
        // Asked at 05:51, "1:30" means this afternoon.
        assertEquals(NilaResponse.Alarm(13, 30), NilaEngine(now = { LocalTime.of(5, 51) }).execute(call))
        // Asked just after midnight, it means tonight at 01:30.
        assertEquals(NilaResponse.Alarm(1, 30), NilaEngine(now = { LocalTime.of(0, 30) }).execute(call))
        // A 24-hour time is taken as said.
        assertEquals(NilaResponse.Alarm(18, 0), NilaEngine(now = { LocalTime.of(5, 51) }).execute(NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 18)))))
    }

    @Test
    fun keywordRulesCatchAlarmOnAndAt() {
        assertTrue(engine.route("put an alarm on 1:30") is NilaIntent.Call)
        assertTrue(engine.route("set alarm at 7") is NilaIntent.Call)
    }

    @Test
    fun chatRepliesThatClaimAnActionAreCaught() {
        listOf(
            "I've set an alarm for 1:30 for you!",
            "Done! Your alarm is set for 1:30.",
            "Alarm set for 1:30.",
            "I have ordered the biryani.",
            "Your reminder has been scheduled.",
        ).forEach { assertTrue(it, claimsAction(it)) }
        listOf(
            "I can set an alarm for you if you tell me the time.",
            "NILA cannot turn on the flashlight right now, but I can set an alarm for you.",
            "Hello! I'm NILA, your friendly assistant.",
            "What time should I set it for?",
        ).forEach { assertFalse(it, claimsAction(it)) }
    }

    @Test
    fun removingAnAlarmIsRoutedAndNeverConfusedWithSettingOne() {
        assertEquals(NilaIntent.Call(ToolCall("remove_alarm", mapOf("hour" to 1, "minute" to 30))), engine.route("cancel the alarm for 1:30"))
        assertEquals(NilaIntent.Call(ToolCall("remove_alarm", emptyMap())), engine.route("turn off my alarm"))
        assertEquals(NilaIntent.Call(ToolCall("remove_alarm", mapOf("hour" to 7, "minute" to 0, "meridiem" to "am"))), engine.route("delete alarm at 7 am"))
        // Setting still sets.
        assertTrue((engine.route("set an alarm for 7 am") as NilaIntent.Call).call.capabilityId == "create_alarm")
    }

    @Test
    fun removingAnAlarmStopsAtConfirmationShowingTheExactTime() {
        val engine = NilaEngine(now = { LocalTime.of(5, 51) })
        val pending = engine.execute(engine.route("remove the alarm at 1:30")) as NilaResponse.NeedsConfirmation
        // Same next-occurrence rule as setting: 1:30 asked at 05:51 is 13:30.
        assertEquals(listOf("Turn off" to "The 13:30 alarm"), pending.lines)
        // The card title is for people, not the instructions written for the model.
        assertEquals("Turn off an alarm", pending.capability.label)
        assertEquals(NilaResponse.DismissAlarm(13, 30), engine.confirm(pending.token))
        // Single use: a second tap removes nothing.
        assertEquals(NilaResponse.Text("This confirmation was already used. Nothing was done."), engine.confirm(pending.token))
    }

    @Test
    fun removingWithoutATimeMeansTheNextAlarm() {
        val pending = engine.execute(engine.route("cancel my alarm")) as NilaResponse.NeedsConfirmation
        assertEquals(listOf("Turn off" to "Your next alarm"), pending.lines)
        assertEquals(NilaResponse.DismissAlarm(null, 0), engine.confirm(pending.token))
    }

    @Test
    fun calculatorStaysAheadOfModels() {
        assertEquals(NilaResponse.Text("18% of 4250 is 765"), engine.calculate("What is 18% of 4250?"))
        assertEquals(null, engine.calculate("hi"))
    }
}
