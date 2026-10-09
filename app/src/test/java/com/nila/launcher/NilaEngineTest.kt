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
        val order = CapabilityDescriptor(
            id = "place_order",
            description = "Place the order",
            params = listOf(
                Param("merchant", ParamType.STRING, label = "Merchant"),
                Param("items", ParamType.STRING, label = "Items"),
                Param("total_paise", ParamType.PAISE, label = "Total"),
            ),
            permission = PermissionClass.COMMIT,
        )
        val engine = NilaEngine(listOf(order))
        val call = ToolCall("place_order", mapOf("total_paise" to 12345600, "merchant" to "Meghana Foods", "items" to "1 chicken biryani"))

        assertEquals(
            NilaResponse.NeedsConfirmation(
                call = call,
                capability = order,
                lines = listOf("Merchant" to "Meghana Foods", "Items" to "1 chicken biryani", "Total" to "₹1,23,456.00"),
            ),
            engine.execute(NilaIntent.Call(call)),
        )
        // Confirmation keeps the engine's registry, so the tool is found; it has no executor and fails safely.
        assertEquals(
            NilaResponse.Text("I can't do that yet. Nothing was done."),
            engine.execute(NilaIntent.Call(call), confirmed = true),
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

