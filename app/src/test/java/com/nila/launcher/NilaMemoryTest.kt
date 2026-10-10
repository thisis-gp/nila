package com.nila.launcher

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NilaMemoryTest {
    private fun fact(text: String, kind: MemoryKind, savedAt: Long = 0) = MemoryFact("id-$text", text, kind, savedAt)

    // 2026-10-13 is a Tuesday.
    private val tuesdayMorning = LocalDateTime.of(2026, 10, 13, 9, 0)
    private val wednesdayMorning = LocalDateTime.of(2026, 10, 14, 9, 0)

    @Test
    fun savingKeepsEveryOtherFactAndSkipsRepeats() {
        val first = withFact(emptyList(), fact("I like filter coffee", MemoryKind.Preference))
        val both = withFact(first, fact("I'm allergic to peanuts", MemoryKind.Allergy))
        assertEquals(2, both.size)
        assertEquals(both, withFact(both, fact("  i like FILTER coffee. ", MemoryKind.Other)))
        assertEquals(both, withFact(both, fact(" ", MemoryKind.Other)))
    }

    @Test
    fun theOldSingleFactMigratesOnceAsOther() {
        val migratedOnce = migrated(emptyList(), "Dinner is at 7", savedAt = 5)
        assertEquals(listOf("Dinner is at 7"), migratedOnce.map { it.text })
        assertEquals(MemoryKind.Other, migratedOnce.single().kind)
        assertEquals(migratedOnce, migrated(migratedOnce, "Dinner is at 7", savedAt = 6))
        assertEquals(emptyList<MemoryFact>(), migrated(emptyList(), null, savedAt = 5))
    }

    @Test
    fun anUnknownKindFallsBackToOther() {
        assertEquals(MemoryKind.Allergy, MemoryKind.from(" allergy "))
        assertEquals(MemoryKind.Other, MemoryKind.from("food"))
        assertEquals(MemoryKind.Other, MemoryKind.from(null))
        val engine = NilaEngine()
        assertEquals(
            NilaResponse.Remember("I'm allergic to peanuts", MemoryKind.Allergy),
            engine.execute(NilaIntent.Call(ToolCall("remember_fact", mapOf("fact" to "I'm allergic to peanuts", "kind" to "Allergy")))),
        )
        assertEquals(
            NilaResponse.Remember("Gate code 4521", MemoryKind.Other),
            engine.execute(NilaIntent.Call(ToolCall("remember_fact", mapOf("fact" to "Gate code 4521", "kind" to "secret")))),
        )
    }

    @Test
    fun aLikedDishIsBoostedWithItsReason() {
        val facts = listOf(fact("I really like filter coffee", MemoryKind.Preference))
        assertEquals("You like filter coffee", memoryBoost("Madras Filter Coffee", facts))
        assertNull(memoryBoost("Cold Coffee", facts))
        assertNull(memoryBoost("Filter Coffee", listOf(fact("Filter coffee", MemoryKind.Other))))
        assertEquals("You like biryani", memoryBoost("Chicken Biryani", listOf(fact("I love biryani", MemoryKind.Preference))))
    }

    @Test
    fun allergensAreFlaggedAndNeverTheTopPick() {
        val facts = listOf(fact("I'm allergic to peanuts", MemoryKind.Allergy))
        assertEquals("check: peanuts", allergyCheck("Peanut Chikki Shake", facts))
        assertNull(allergyCheck("Mango Lassi", facts))

        fun dish(name: String, rating: String) = FoodDish(
            FoodItem(name, name, "₹150", true, null, "", false, "{}"),
            FoodRestaurant(name, "Place $name", "", rating, "", "", "", null, "", open = true),
            "",
        )
        val ranked = rankDishes(
            listOf(dish("Peanut Butter Shake", "4.8"), dish("Mango Shake", "4.1")),
            NilaResponse.FoodSearch("shake"),
            FoodMemory(facts, vegPreferred = false),
        )
        assertEquals("Mango Shake", ranked.first().item.name)
        assertEquals("check: peanuts", ranked.last().caution)
        assertTrue(ranked.last().reason.endsWith("check: peanuts"))
    }

    @Test
    fun aLikedDishLeadsWithYouLike() {
        val facts = listOf(fact("I like masala dosa", MemoryKind.Preference))
        fun dish(name: String, rating: String) = FoodDish(
            FoodItem(name, name, "₹120", true, null, "", false, "{}"),
            FoodRestaurant(name, "Place $name", "", rating, "", "", "", null, "", open = true),
            "",
        )
        val ranked = rankDishes(listOf(dish("Plain Dosa", "4.7"), dish("Masala Dosa", "4.2")), NilaResponse.FoodSearch("dosa"), FoodMemory(facts, vegPreferred = false))
        assertEquals("Masala Dosa", ranked.first().item.name)
        assertTrue(ranked.first().reason.startsWith("You like masala dosa"))
    }

    @Test
    fun aVegDietAppliesOnlyOnItsOwnDay() {
        val facts = listOf(fact("I eat veg on Tuesdays", MemoryKind.Diet))
        assertTrue(wantsVeg(facts, tuesdayMorning))
        assertFalse(wantsVeg(facts, wednesdayMorning))
        assertTrue(wantsVeg(listOf(fact("I'm vegetarian", MemoryKind.Diet)), wednesdayMorning))
        assertFalse(wantsVeg(listOf(fact("I eat non-veg on Tuesdays", MemoryKind.Diet)), tuesdayMorning))
        assertFalse(wantsVeg(listOf(fact("Veg on weekend evenings", MemoryKind.Diet)), tuesdayMorning))
        assertTrue(appliesAt("weekday mornings", tuesdayMorning))
    }

    @Test
    fun theMorningSuggestionComesFromTheNewestLike() {
        val facts = listOf(
            fact("I like masala dosa", MemoryKind.Preference, savedAt = 1),
            fact("I like filter coffee", MemoryKind.Preference, savedAt = 2),
            fact("Office is in Koramangala", MemoryKind.Place, savedAt = 3),
        )
        assertEquals(MemorySuggestion("Filter coffee for breakfast?", "order filter coffee"), morningSuggestion(facts, LocalTime.of(8, 30)))
        assertNull(morningSuggestion(facts, LocalTime.of(11, 0)))
        assertNull(morningSuggestion(facts.filter { it.kind == MemoryKind.Place }, LocalTime.of(8, 30)))
    }

    @Test
    fun chatGetsAtMostFivePersonalFactsFirst() {
        val few = listOf(fact("Gym at 6", MemoryKind.Routine))
        assertEquals(few, chatFacts(few))
        val many = listOf(
            fact("Gym at 6", MemoryKind.Routine, savedAt = 9),
            fact("Office in Koramangala", MemoryKind.Place, savedAt = 8),
            fact("I like dosa", MemoryKind.Preference, savedAt = 1),
            fact("Allergic to peanuts", MemoryKind.Allergy, savedAt = 2),
            fact("Veg on Tuesdays", MemoryKind.Diet, savedAt = 3),
            fact("Mom's birthday is in May", MemoryKind.Other, savedAt = 7),
        )
        assertEquals(
            listOf("Veg on Tuesdays", "Allergic to peanuts", "I like dosa", "Gym at 6", "Office in Koramangala"),
            chatFacts(many).map { it.text },
        )
        assertEquals("persona", personaWith("persona", emptyList()))
        assertTrue(personaWith("persona", few).contains("Things the user told you: Gym at 6."))
    }

    @Test
    fun listsWhatItRemembers() {
        assertEquals("You have not asked me to remember anything yet.", memorySummary(emptyList()))
        assertEquals("You asked me to remember:\n• Allergic to peanuts (allergy)", memorySummary(listOf(fact("Allergic to peanuts", MemoryKind.Allergy))))
    }
}
