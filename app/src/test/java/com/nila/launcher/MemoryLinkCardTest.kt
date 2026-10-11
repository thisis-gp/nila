package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryLinkCardTest {
    @Test
    fun theReplyUsesAPronounInsteadOfRepeatingThePerson() {
        assertEquals("I'll remember she loves pani puri.", rememberLine("sister", "loves pani puri"))
        assertEquals("I'll remember he likes filter coffee.", rememberLine("appa", "likes filter coffee"))
        assertEquals("I'll remember they love biryani.", rememberLine("priya", "loves biryani"))
    }

    @Test
    fun theUsersOwnFactsReadAsYou() {
        assertEquals("I'll remember you like filter coffee.", rememberLine(ME, "I like filter coffee"))
        assertEquals("I'll remember you're vegetarian.", rememberLine(ME, "Vegetarian"))
    }

    @Test
    fun aContactWithoutAPhotoShowsTheirInitial() {
        assertEquals("D", contactInitial("divya"))
        assertEquals("?", contactInitial(" "))
    }
}
