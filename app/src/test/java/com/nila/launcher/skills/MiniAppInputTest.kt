package com.nila.launcher.skills

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAppInputTest {
    @Test
    fun moneyTypedInRupeesIsSavedInPaise() {
        assertEquals(JsonPrimitive(50_000L), typedInput("500", "money"))
        assertEquals(JsonPrimitive(12_050L), typedInput("120.50", "money"))
    }

    @Test
    fun numbersAreNumbersAndTheRestStaysText() {
        assertEquals(JsonPrimitive(72.5), typedInput(" 72.5 ", "number"))
        assertEquals(JsonPrimitive("abc"), typedInput("abc", "number"))
        assertEquals(JsonPrimitive("Dune"), typedInput("Dune", "text"))
    }
}
