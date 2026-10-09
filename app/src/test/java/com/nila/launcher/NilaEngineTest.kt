package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
            NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 6, "minute" to 30, "meridiem" to "pm"))),
            engine.route("Set an alarm for 6:30 pm")
        )
    }

    @Test
    fun rejectsHoursOutsideOneToTwelveWithMeridiem() {
        val invalid = NilaResponse.Text("That alarm time is not valid.")
        assertEquals(invalid, engine.respond("Set an alarm for 13 pm"))
        assertEquals(invalid, engine.respond("Set an alarm for 13 am"))
        assertEquals(invalid, engine.respond("Set an alarm for 0 am"))
    }

    @Test
    fun convertsTwelveAmAndPm() {
        assertEquals(NilaResponse.Alarm(0, 15), engine.respond("Set an alarm for 12:15 am"))
        assertEquals(NilaResponse.Alarm(12, 15), engine.respond("Set an alarm for 12:15 pm"))
        assertEquals(NilaResponse.Alarm(18, 30), engine.respond("Set an alarm for 6:30 pm"))
    }

    @Test
    fun commitCallWaitsForConfirmation() {
        // No executor is registered for this tool, so running it throws. Only confirmation may reach that point.
        val order = CapabilityDescriptor(
            id = "place_order",
            description = "Place the order",
            params = listOf(Param("merchant", ParamType.STRING), Param("items", ParamType.STRING), Param("total_paise", ParamType.INT)),
            permission = PermissionClass.COMMIT,
        )
        val registry = engine.capabilities + (order.id to order)
        val call = ToolCall("place_order", mapOf("total_paise" to 23900, "merchant" to "Meghana Foods", "items" to "1 chicken biryani"))

        assertEquals(
            NilaResponse.NeedsConfirmation(
                call = call,
                capability = order,
                lines = listOf("merchant" to "Meghana Foods", "items" to "1 chicken biryani", "total_paise" to "23900"),
            ),
            engine.execute(NilaIntent.Call(call), registry = registry),
        )
        assertThrows(IllegalStateException::class.java) {
            engine.execute(NilaIntent.Call(call), confirmed = true, registry = registry)
        }
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

