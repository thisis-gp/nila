package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteTest {
    private val engine = NilaEngine()

    @Test
    fun cleansGemmasCommand() {
        assertEquals("turn off the flashlight", cleanCommand("turn off the flashlight."))
        assertEquals("take a note that 2 apples", cleanCommand("Command: \"take a note that 2 apples\"\n(explanation)"))
        assertNull(cleanCommand("CHAT"))
        assertNull(cleanCommand("  chat \n"))
        assertNull(cleanCommand("   "))
    }

    @Test
    fun rewrittenCommandsNeverInventNumbers() {
        // "yes" after NILA asked about 6:30: the numbers come from the conversation.
        assertTrue(numbersSaid("set an alarm for 6:30 pm", "yes Do you want an alarm at 6:30 pm?"))
        assertTrue(numbersSaid("take a note that 2 apples", "i need 2 apples put in the notes"))
        assertFalse(numbersSaid("set an alarm for 7 am", "wake me up early"))
        assertTrue(numbersSaid("turn off the flashlight", "yes"))
        // A huge amount is compared as text, never parsed: it used to throw NumberFormatException.
        assertTrue(numbersSaid("pay 123456789012345678901234567890", "pay 123456789012345678901234567890 rupees"))
        assertFalse(numbersSaid("pay 123456789012345678901234567891", "pay 123456789012345678901234567890"))
        assertTrue(numbersSaid("set an alarm for 7:05 am", "wake me at 07:05"))
    }

    @Test
    fun onlyAReplyWithoutNumbersBorrowsThemFromTheLastExchange() {
        val earlier = "set an alarm for 7 am" to "Alarm set for 07:00 in your Clock."
        // The request says 5: an old 7 must not approve a rewrite to 7 minutes.
        assertFalse(numbersSaid("set a timer for 7 minutes", groundingText("timer 5 minutes", earlier)))
        assertTrue(numbersSaid("set a timer for 5 minutes", groundingText("timer 5 minutes", earlier)))
        // "yes" answers the last exchange, so its numbers count.
        val asked = "wake me at 6:30" to "Do you want an alarm at 6:30 pm?"
        assertTrue(numbersSaid("set an alarm for 6:30 pm", groundingText("yes", asked)))
        assertFalse(numbersSaid("set an alarm for 6:30 pm", groundingText("yes", null)))
        // A fresh request with a number word is not a follow-up: an earlier 7 cannot approve "six" as 7.
        val alarmAt7 = "set an alarm for 7 am" to "Alarm set for 07:00 in your Clock."
        assertFalse(numbersSaid("set an alarm for 7 am", groundingText("set an alarm at six", alarmAt7)))
        assertFalse(numbersSaid("set a timer for 7 minutes", groundingText("start a timer", alarmAt7)))
        assertFalse(numbersSaid("set an alarm for 7 pm", groundingText("make it seven pm", alarmAt7)))
        // References to the last exchange still borrow.
        assertTrue(numbersSaid("set an alarm for 7 pm", groundingText("make it pm", alarmAt7)))
        assertTrue(numbersSaid("set an alarm for 7 am", groundingText("do that again", alarmAt7)))
        // Hindi and Malayalam number words are new values too: an old 7 cannot satisfy them.
        listOf("haan, saat baje", "haan, do baje", "हाँ, सात बजे", "ശരി, ഏഴ് മണി", "it ezhu mani", "हाँ ७ बजे").forEach {
            assertFalse(it, numbersSaid("set an alarm for 7 am", groundingText(it, alarmAt7)))
        }
        assertTrue(numbersSaid("set an alarm for 7 am", groundingText("haan", alarmAt7)))
        // Huge numbers never throw on the FunctionGemma path either.
        assertFalse(numbersGrounded(mapOf("amount" to 5), "pay 123456789012345678901234567890"))
    }

    @Test
    fun aNoteIsNeverAZeptoOrder() {
        // "i need" is a Zepto trigger; with "notes" in it, it is a note.
        assertNull(extractOrderItem("i need 2 apples put in the notes"))
        assertEquals("milk 1L", extractOrderItem("i need milk 1L"))
    }

    @Test
    fun rewrittenCommandsReachTheRules() {
        // What Gemma is asked to produce must route exactly like typed text.
        assertEquals(Torch(false), engine.respond("turn off the flashlight"))
        assertEquals(Note("2 apples"), engine.respond("take a note that 2 apples"))
        assertEquals(ShowNotes, engine.respond("show my notes"))
        assertEquals(ShowNotes, engine.respond("what's in my notes"))
        assertEquals(SoundChange("ring"), engine.respond("ring mode"))
    }
}
