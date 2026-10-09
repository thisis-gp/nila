package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NilaEngineTest {
    private val engine = NilaEngine()

    @Test
    fun calculatesPercentage() {
        assertEquals(
            NilaResponse.Text("18% of 4250 is 765"),
            engine.respond("What is 18% of 4250?")
        )
    }

    @Test
    fun parsesAlarm() {
        assertEquals(
            NilaResponse.Alarm(6, 30),
            engine.respond("Set an alarm for 6:30 am")
        )
    }

    @Test
    fun routesAlarmToToolCall() {
        assertEquals(
            NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 18, "minute" to 30))),
            engine.route("Set an alarm for 6:30 pm")
        )
    }

    @Test
    fun rejectsInvalidAlarmTime() {
        assertEquals(NilaResponse.Text("That alarm time is not valid."), engine.respond("Set an alarm for 25:00"))
    }

    @Test
    fun rejectsUnregisteredToolCall() {
        val response = engine.execute(NilaIntent.Call(ToolCall("place_order", mapOf("cart" to "1"))))
        assertEquals(NilaResponse.Text("I can't do that. Unknown tool place_order."), response)
    }

    @Test
    fun createsFoodComparison() {
        val response = engine.respond("Order chicken biryani under 250")
        assertTrue(response is NilaResponse.Choices)
        assertEquals(2, (response as NilaResponse.Choices).items.size)
    }

    @Test
    fun remembersExplicitFact() {
        assertEquals(
            NilaResponse.Remember("I prefer vegetarian food on Fridays"),
            engine.respond("Remember that I prefer vegetarian food on Fridays")
        )
    }
}

