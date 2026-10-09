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
    fun createsFoodComparison() {
        val response = engine.respond("Order chicken biryani under 250")
        assertTrue(response is NilaResponse.Choices)
        assertEquals(2, (response as NilaResponse.Choices).items.size)
    }

    @Test
    fun preparesFoodChoiceWithoutOrdering() {
        val choices = engine.respond("Order chicken biryani under 250") as NilaResponse.Choices

        assertEquals(
            NilaResponse.Confirmation(
                title = "Review Meghana Foods",
                detail = "Chicken boneless biryani · 4.6★ · 24 min",
                price = "₹239",
            ),
            engine.prepare(choices.items.first()),
        )
    }

    @Test
    fun remembersExplicitFact() {
        assertEquals(
            NilaResponse.Remember("I prefer vegetarian food on Fridays"),
            engine.respond("Remember that I prefer vegetarian food on Fridays")
        )
    }
}

