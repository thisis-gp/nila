package com.nila.launcher

import org.junit.Test
import org.junit.Assert.assertEquals

class MemoryLinkCardTest {
    @Test
    fun headlineNamesWhoTheFactIsFor() {
        assertEquals("Saved for you", savedHeadline(ME))
        assertEquals("Saved for your wife", savedHeadline("wife"))
        assertEquals("Saved for Priya", savedHeadline("priya"))
    }

    @Test
    fun questionNamesThePerson() {
        assertEquals("Which contact is your wife?", linkQuestion("wife"))
        assertEquals("Which contact is Priya?", linkQuestion("priya"))
    }

    @Test
    fun initialIsUppercaseOrQuestionMark() {
        assertEquals("D", contactInitial(" divya"))
        assertEquals("?", contactInitial(""))
    }
}
