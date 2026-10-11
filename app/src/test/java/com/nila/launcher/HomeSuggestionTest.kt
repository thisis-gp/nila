package com.nila.launcher

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSuggestionTest {
    private fun fact(text: String, kind: MemoryKind, savedAt: Long = 0) = MemoryFact("id-$text", text, kind, savedAt)
    private fun at(hour: Int) = LocalDateTime.of(2026, 10, 13, hour, 30)

    private val dosa = fact("I like masala dosa for breakfast", MemoryKind.Preference)

    @Test
    fun aLikedDishForThisMealIsSuggestedWithAReason() {
        val s = homeSuggestion(listOf(dosa), at(8))!!
        assertEquals("Masala dosa for breakfast?", s.label)
        assertEquals("order masala dosa", s.request)
        assertEquals("Because you like masala dosa for breakfast", s.reason)
    }

    @Test
    fun withoutAMatchingLikeEachMealHasADefault() {
        assertEquals("Idli for breakfast?", homeSuggestion(emptyList(), at(7))!!.label)
        assertEquals("order veg thali", homeSuggestion(emptyList(), at(13))!!.request)
        assertEquals("order samosa", homeSuggestion(listOf(dosa), at(17))!!.request)
        assertEquals("Something light for dinner?", homeSuggestion(emptyList(), at(20))!!.label)
        assertNull(homeSuggestion(emptyList(), at(8))!!.reason)
    }

    @Test
    fun outsideTheMealWindowsThereIsNoSuggestion() {
        assertNull(homeSuggestion(listOf(dosa), at(5)))
        assertNull(homeSuggestion(listOf(dosa), at(23)))
        assertNull(homeSuggestion(listOf(dosa), at(2)))
    }

    @Test
    fun vegetarianRulesOutMeatAndSaysWhy() {
        val facts = listOf(fact("I'm vegetarian", MemoryKind.Diet), fact("I like chicken biryani for lunch", MemoryKind.Preference))
        val s = homeSuggestion(facts, at(13))!!
        assertEquals("order veg thali", s.request)
        assertEquals("You're vegetarian, so veg places only", s.reason)
    }

    @Test
    fun anAllergenIsNeverSuggested() {
        val facts = listOf(fact("Allergic to peanuts", MemoryKind.Allergy), fact("I like peanut chikki as a snack", MemoryKind.Preference))
        assertEquals("order samosa", homeSuggestion(facts, at(17))!!.request)
    }

    @Test
    fun likesForAnotherMealAreNotUsed() {
        assertEquals("order veg thali", homeSuggestion(listOf(dosa), at(13))!!.request)
    }

    @Test
    fun reasonsAreWrittenAsBecauseYou() {
        assertEquals("Because you like masala dosa", becauseYou("I like masala dosa."))
        assertEquals("Because you love filter coffee", becauseYou("I love filter coffee"))
        assertEquals("Because you're vegetarian", becauseYou("I'm vegetarian"))
        assertEquals("Because you're allergic to peanuts", becauseYou("I’m allergic to peanuts"))
        assertEquals("Because your wife is Priya", becauseYou("My wife is Priya"))
        assertEquals("From what you told me", becauseYou("Dinner at 7"))
    }

    @Test
    fun dishPhotosMatchOnlyKnownDishes() {
        assertEquals("biryani", dishPhotoKey("Chicken Biryani"))
        assertEquals("salad", dishPhotoKey("green salad"))
        assertNull(dishPhotoKey("idli"))
        assertNull(dishPhotoKey(null))
    }
}
