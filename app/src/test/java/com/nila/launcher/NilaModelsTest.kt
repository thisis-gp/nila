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
    fun aReplyThatReadsBackThePersonaIsDropped() {
        assertTrue(echoesInstructions("Okay, I understand. I will respond in one to three short, warm sentences in the same language."))
        assertFalse(echoesInstructions("Sure, an alarm for six is a good start to the day."))
    }

    @Test
    fun modelStatusNamesOnlyValidatedGeneralAssistants() {
        assertEquals(
            "Gemma 4 E2B · voice ready",
            modelStatus(ModelCatalog.GEMMA_4_E2B),
        )
        assertEquals("No compatible general assistant model installed", modelStatus(null))
        assertEquals(
            "Gemma 4 E2B · voice ready · assistant reply failed; using fallback",
            modelStatus(ModelCatalog.GEMMA_4_E2B, inferenceFailed = true),
        )
    }

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
        listOf(
            "hi", "Hello there", "who are you", "What can you do?", "what's your name", "thanks!", "hi nila",
            "how are you doing today", "hello how are you", "hey what's up", "hi good morning",
            "how can you help me?", "how can you help", "what do you do",
            // Real one seen on-device, typo and all: this used to get routed to show_notes.
            "what is teh use of nila?", "what is the use of nila?", "why should I use nila", "is nila free",
        ).forEach {
            assertTrue(it, engine.isSmallTalk(it))
        }
        listOf(
            "i'm hungry", "wake me up at 6", "high protein dinner", "open youtube", "hey, wake me up at 7",
            "help me set a timer for 5 minutes", "hi can you put milk in my notes", "hey nila play music",
            "hi how are you, remind me tomorrow",
            // Guru's review on #51: these name NILA but are action requests, not questions about it.
            "can NILA call Mom?", "can NILA remind me at 6?", "can NILA open YouTube?",
        ).forEach {
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
    fun invalidMinutesAreRejectedNotCrashed() {
        // Without am/pm the next-occurrence check used to build LocalTime.of(5, 99) and throw.
        val invalid = NilaResponse.Text("That alarm time is not valid.")
        val engine = NilaEngine(now = { LocalTime.of(5, 51) })
        assertEquals(invalid, engine.respond("set an alarm for 5:99"))
        assertEquals(invalid, engine.execute(NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 5, "minute" to 99)))))
        assertEquals(invalid, engine.execute(NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 13, "minute" to 75)))))
    }

    @Test
    fun keywordRulesCatchAlarmOnAndAt() {
        assertTrue(engine.route("put an alarm on 1:30") is NilaIntent.Call)
        assertTrue(engine.route("set alarm at 7") is NilaIntent.Call)
    }

    @Test
    fun recipeQuestionsAndFoodOrGroceryOrdersStayOutOfTheDeterministicRules() {
        // search_food/search_groceries are purely model-routed (REWRITE, then chat's own tool-calling) —
        // confirmed here by staying null; the model's own CHAT-vs-order judgment is verified live on
        // device, not by a unit test, since it needs a real Gemma inference.
        listOf(
            "what can I cook with rice and dal",
            "order paneer butter masala",
            "buy ingredients for biryani",
        ).forEach { assertFalse(it, engine.route(it) is NilaIntent.Call) }
    }

    @Test
    fun chatRepliesThatClaimAnActionAreCaught() {
        listOf(
            "I've set an alarm for 1:30 for you!",
            "Done! Your alarm is set for 1:30.",
            "Alarm set for 1:30.",
            "I have ordered the biryani.",
            "Your reminder has been scheduled.",
            "Okay, I'm turning off the torch.",
            "I've turned off the flashlight for you.",
            "The flashlight is now off.",
        ).forEach { assertTrue(it, claimsAction(it)) }
        listOf(
            "I can set an alarm for you if you tell me the time.",
            "NILA cannot turn on the flashlight right now, but I can set an alarm for you.",
            "Hello! I'm NILA, your friendly assistant.",
            "What time should I set it for?",
            "Say \"turn on the flashlight\" and I'll do it.",
        ).forEach { assertFalse(it, claimsAction(it)) }
    }

    @Test
    fun rawToolCallSyntaxLeakedOntoTheTextChannelIsCaughtAnywhereInTheReply() {
        listOf(
            "<|tool_call>call:remember_fact{fact:<|\"|>The Ma Triangle of Cooking is a concept...",
            "call:search_food{query:\"biryani\"}",
            // Real text can precede the leak; must still be caught, not just at the start.
            "Sure, here's what I found! <|tool_call>call:web_search{query:\"x\"}",
        ).forEach { assertTrue(it, looksLikeRawToolCall(it)) }
        listOf(
            "You can make a simple omelet with milk and bread.",
            "I'll call you back once the timer is up.",
        ).forEach { assertFalse(it, looksLikeRawToolCall(it)) }
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
        // A follow-up keeps the amount and changes the rate, worked out here rather than by Gemma.
        assertEquals(NilaResponse.Text("20% of 4250 is 850"), engine.calculateFollowUp("and 20%", listOf("18% of 4250 is 765", "what is 18% of 4250")))
        // A second follow-up: the turn before has no amount, NILA's answer to it does (seen on the iQOO 15).
        assertEquals(
            NilaResponse.Text("15% of 4250 is 637.5"),
            engine.calculateFollowUp("what about 15 percent?", listOf("20% of 4250 is 850", "and 20%", "18% of 4250 is 765", "what is 18% of 4250")),
        )
        assertEquals(null, engine.calculateFollowUp("and 20%", listOf("Timer started.", "set a timer for 5 minutes")))
        assertEquals(null, engine.calculateFollowUp("set a timer for 20 minutes", listOf("18% of 4250 is 765")))
    }
}
