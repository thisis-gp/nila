package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyNilaTest {
    private val wifeLikes = newFact("loves butter chicken", MemoryKind.Preference, savedAt = 1, about = "my wife")
    private val iAmVeg = newFact("vegetarian on Tuesdays", MemoryKind.Diet, savedAt = 2)
    private val ammaAllergy = newFact("allergic to peanuts", MemoryKind.Allergy, savedAt = 3, about = "Amma")

    @Test
    fun everyFactKnowsWhoItIsAbout() {
        assertEquals("wife", wifeLikes.about)
        assertEquals(ME, iAmVeg.about)
        assertEquals("amma", ammaAllergy.about)
        assertEquals(ME, aboutKey("I"))
        assertEquals(ME, aboutKey(null))
        assertEquals("Wife", aboutLabel("wife"))
        assertEquals("You", aboutLabel(ME))
    }

    @Test
    fun onlyPersonalMessagesAreReadForDetails() {
        assertTrue(mightBePersonal("my wife loves butter chicken"))
        assertTrue(mightBePersonal("I'm allergic to peanuts"))
        assertTrue(mightBePersonal("my birthday is on 12 March"))
        assertFalse(mightBePersonal("set an alarm for 6:30"))
        assertFalse(mightBePersonal("order biryani"))
    }

    @Test
    fun aSavedFactIsMadeOfTheUsersOwnWords() {
        assertTrue(groundedFact("loves butter chicken", "my wife loves butter chicken"))
        // Gemma must not invent a dish the user never said.
        assertFalse(groundedFact("loves paneer tikka", "my wife loves butter chicken"))
        assertFalse(groundedFact("", "my wife loves butter chicken"))
    }

    @Test
    fun aFavouriteBecomesTheDish() {
        val facts = listOf(wifeLikes, iAmVeg, ammaAllergy)
        assertEquals("wife", favouriteOf("order my wife's favourite"))
        assertEquals("order butter chicken", withFavourite("order my wife's favourite", facts))
        assertEquals("order butter chicken", withFavourite("order my wifes favorite dish", facts))
        // Nobody's favourite was asked for, or NILA doesn't know it yet: no guess.
        assertNull(withFavourite("order biryani", facts))
        assertNull(withFavourite("order my husband's favourite", facts))
    }

    @Test
    fun nilaSaysWhatItKnowsAboutSomeone() {
        val facts = listOf(wifeLikes, iAmVeg, ammaAllergy)
        assertEquals("wife", askedAbout("what do you know about my wife?"))
        assertEquals(ME, askedAbout("what do you know about me"))
        assertTrue(personSummary("wife", facts).contains("loves butter chicken"))
        assertTrue(personSummary("husband", facts).startsWith("I don't know anything about your husband"))
    }

    @Test
    fun theUsersPeopleAreGroupedWithTheUserFirst() {
        val grouped = factsByPerson(listOf(ammaAllergy, wifeLikes, iAmVeg))
        assertEquals(listOf(ME, "wife", "amma"), grouped.map { it.first })
    }

    @Test
    fun someoneElsesLikesNeverChangeTheUsersOwnResults() {
        // The wife's butter chicken is hers: the user's own searches are not boosted by it.
        assertNull(memoryBoost("Butter Chicken Curry", listOf(wifeLikes)))
        val mine = newFact("loves butter chicken", MemoryKind.Preference, savedAt = 4)
        assertEquals("You like butter chicken", memoryBoost("Butter Chicken Curry", listOf(mine)))
    }
}
