package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyNilaFactsTest {
    private fun fact(text: String, about: String = ME) = MemoryFact("id-$text", text, MemoryKind.Other, 0, about)

    @Test
    fun factsReadAsCleanLines() {
        assertEquals("Likes filter coffee", factLine("I like filter coffee"))
        assertEquals("Masala dosa for breakfast", factLine("I like masala dosa for breakfast"))
        assertEquals("Vegetarian", factLine("I'm vegetarian"))
        assertEquals("Allergic to peanuts", factLine("I am allergic to peanuts."))
        assertEquals("Doesn't eat onion", factLine("I don't eat onion"))
        assertEquals("Birthday is 5 May", factLine("My birthday is 5 May"))
        assertEquals("Loves butter chicken", factLine("Loves butter chicken"))
    }

    @Test
    fun onlyDietAndAllergyGetATag() {
        assertEquals("Diet", factTag(MemoryKind.Diet))
        assertEquals("Allergy", factTag(MemoryKind.Allergy))
        assertNull(factTag(MemoryKind.Preference))
        assertNull(factTag(MemoryKind.Other))
    }

    @Test
    fun theUsersNameComesFromTheNameFact() {
        val name = fact("My name is Gurupriyan")
        assertEquals("Gurupriyan", userName(listOf(fact("Vegetarian"), name)))
        assertTrue(isNameFact(name))
        assertFalse(isNameFact(fact("My name is Priya", about = "wife")))
        assertNull(userName(listOf(fact("Vegetarian"))))
    }

    @Test
    fun countsAreSingularForOne() {
        assertEquals("1 thing NILA knows", knownCount(1))
        assertEquals("3 things NILA knows", knownCount(3))
    }
}
