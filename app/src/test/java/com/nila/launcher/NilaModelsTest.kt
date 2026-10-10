package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun calculatorStaysAheadOfModels() {
        assertEquals(NilaResponse.Text("18% of 4250 is 765"), engine.calculate("What is 18% of 4250?"))
        assertEquals(null, engine.calculate("hi"))
    }
}
