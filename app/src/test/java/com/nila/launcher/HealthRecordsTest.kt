package com.nila.launcher

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRecordsTest {
    // Sunday 11 October 2026.
    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun aHospitalVisitInChatIsAHealthEntry() {
        assertTrue(isHealthMention("last week I went to the hospital and this is the report they gave"))
        assertTrue(isHealthMention("got my blood test report today"))
        assertTrue(isHealthMention("open my health tracker"))
        assertFalse(isHealthMention("order biryani"))
        assertFalse(isHealthMention("take me to Indiranagar"))
        // A health word inside another request is that request.
        assertFalse(isHealthMention("remind me to go to the doctor tomorrow"))
        assertFalse(isHealthMention("call the hospital"))
        assertFalse(isHealthMention("book a doctor appointment"))
    }

    @Test
    fun theKindFollowsTheWords() {
        assertEquals(HealthKind.Visit, healthKindFor("last week I went to the hospital and this is the report"))
        assertEquals(HealthKind.Report, healthKindFor("got my blood test report today"))
    }

    @Test
    fun theDayIsTheOneTheUserSaid() {
        assertEquals(today.minusWeeks(1), healthDate("last week I went to the hospital", today))
        assertEquals(today.minusDays(1), healthDate("went to the doctor yesterday", today))
        assertEquals(today.minusDays(3), healthDate("3 days ago I visited the clinic", today))
        // A weekday means the last one before today: Thursday 8 October.
        assertEquals(LocalDate.of(2026, 10, 8), healthDate("went to the hospital on thursday", today))
        assertEquals(today, healthDate("got my blood test report", today))
    }

    @Test
    fun recordsAreNewestFirst() {
        val old = HealthRecord("a", HealthKind.Visit, "Check-up", today.minusMonths(2))
        val new = HealthRecord("b", HealthKind.Report, "Blood test", today.minusWeeks(1))
        assertEquals(listOf("b", "a"), newestFirst(listOf(old, new)).map { it.id })
    }
}
