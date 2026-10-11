package com.nila.launcher

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessTest {
    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun workoutsAndWeightFromChat() {
        fitnessEntry("I ran 5 km in 30 minutes", today)!!.let {
            assertEquals("run", it.activity); assertEquals(5.0, it.km!!, 0.0); assertEquals(30, it.minutes)
        }
        fitnessEntry("did 45 min yoga today", today)!!.let { assertEquals("yoga", it.activity); assertEquals(45, it.minutes) }
        fitnessEntry("I walked 3.5 km yesterday", today)!!.let { assertEquals("walk", it.activity); assertEquals(today.minusDays(1), it.date) }
        fitnessEntry("I worked out for 1.5 hours", today)!!.let { assertEquals("gym", it.activity); assertEquals(90, it.minutes) }
        fitnessEntry("my weight is 72.5 kg", today)!!.let { assertEquals("weight", it.activity); assertEquals(72.5, it.kg!!, 0.0) }
        assertEquals("Run · 5 km · 30 min", entryLine(fitnessEntry("I ran 5 km in 30 minutes", today)!!))
    }

    @Test
    fun requestsAreNotWorkouts() {
        assertNull(fitnessEntry("set a timer for 30 minutes", today))
        assertNull(fitnessEntry("order 2 kg rice", today))
        assertNull(fitnessEntry("find a gym near me", today))
        assertNull(fitnessEntry("calculate weight 70 kg", today))
        assertNull(fitnessEntry("I like running", today))
        assertTrue(asksForSteps("how many steps today"))
        assertFalse(asksForSteps("I walked 3 km today"))
    }

    @Test
    fun stepsCountFromTheStartOfTheDay() {
        var s = stepsAfter(null, counter = 10_000, today = today)
        assertEquals(0, stepsToday(s))
        s = stepsAfter(s, 12_500, today)
        assertEquals(2_500, stepsToday(s))
        // Midnight: yesterday closes at its last reading, today starts from there.
        s = stepsAfter(s, 13_000, today.plusDays(1))
        assertEquals(2_500, s.days[today])
        assertEquals(500, stepsToday(s))
        // The phone restarted: its count went back to 200; today's 500 are kept.
        s = stepsAfter(s, 200, today.plusDays(1))
        assertEquals(500, stepsToday(s))
        s = stepsAfter(s, 1_200, today.plusDays(1))
        assertEquals(1_500, stepsToday(s))
    }

    @Test
    fun theWeekEndsToday() {
        val s = StepState(today, 100, 2_100, mapOf(today.minusDays(1) to 9_000))
        val week = lastDays(s, today)
        assertEquals(7, week.size)
        assertEquals(today to 2_000, week.last())
        assertEquals(today.minusDays(1) to 9_000, week[5])
    }
}
