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
    fun tellingNilaSomethingIsNotARequest() {
        // Seen on the iQOO 15: this started a butter chicken search instead of being remembered.
        assertTrue(isPersonalStatement("my wife loves butter chicken"))
        assertTrue(isPersonalStatement("I'm allergic to peanuts"))
        assertTrue(isPersonalStatement("my mom's birthday is on 12 March"))
        assertFalse(isPersonalStatement("order my wife's favourite"))
        assertFalse(isPersonalStatement("my wife loves butter chicken, order it"))
        assertFalse(isPersonalStatement("order butter chicken"))
        // A personal detail with a request in it is a request.
        assertFalse(isPersonalStatement("I'm in the dark, turn on the flashlight"))
        assertFalse(isPersonalStatement("my mom is calling, call her back"))
        assertFalse(isPersonalStatement("I'm late, send a message to Priya"))
        assertFalse(isPersonalStatement("I'm done, stop the timer"))
        assertFalse(isPersonalStatement("I'm going out, cancel my alarm"))
        assertFalse(isPersonalStatement("my wife is home, switch off the lights"))
    }

    @Test
    fun savedFactsSurviveStorageAndTheMoveToEncryption() {
        val facts = listOf(
            MemoryFact("1", "loves butter chicken", MemoryKind.Preference, 10, "wife"),
            MemoryFact("2", "allergic to peanuts", MemoryKind.Allergy, 20),
        )
        assertEquals(facts, decodeFacts(encodeFacts(facts)))
        // An update from the plain store keeps every fact, including the oldest single one, without duplicates.
        val moved = movedFacts(secure = null, plain = encodeFacts(facts), plainLegacy = "Home is Kondapur", savedAt = 30)
        assertEquals(listOf("loves butter chicken", "allergic to peanuts", "Home is Kondapur"), moved.map { it.text })
        assertEquals("wife", moved.first().about)
        assertEquals(facts, movedFacts(secure = encodeFacts(facts), plain = encodeFacts(facts), plainLegacy = null, savedAt = 30))
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
        assertTrue(personSummary("wife", facts).contains("loves butter chicken", ignoreCase = true))
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
