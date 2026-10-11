package com.nila.launcher

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyNilaUnderstandingTest {
    @Test
    fun gemmasReadingIsParsedOrIgnored() {
        assertEquals(MyNilaRead(MyNilaType.Fact, null), parseMyNilaRead("""{"type": "fact", "date": ""}"""))
        assertEquals(
            MyNilaRead(MyNilaType.Photos, LocalDate.of(2024, 12, 18)),
            parseMyNilaRead("""Sure: {"type":"photos","date":"2024-12-18"}"""),
        )
        assertEquals(MyNilaType.HealthQuestion, parseMyNilaRead("""{"type":"health_question","date":""}""").type)
        // Anything unreadable changes nothing: NILA carries on as before.
        assertEquals(MyNilaRead(MyNilaType.Other, null), parseMyNilaRead("I think this is a fact."))
        assertEquals(MyNilaType.Other, parseMyNilaRead("""{"type":"order_food"}""").type)
        assertNull(parseMyNilaRead("""{"type":"photos","date":"18 Dec"}""").date)
    }

    @Test
    fun aDateIsUsedOnlyWhenTheMessageNamesADay() {
        assertTrue(namesADay("pics from the 18th of december"))
        assertTrue(namesADay("kal ki photos dikhao"))
        assertTrue(namesADay("ഇന്നലത്തെ ഫോട്ടോകൾ"))
        assertFalse(namesADay("show me my photos"))
    }

    @Test
    fun healthAnswersComeFromTheRecordsOnly() {
        val records = listOf(
            HealthRecord("a", HealthKind.Report, "Blood test", LocalDate.of(2026, 10, 4), place = "Apollo", notes = "Haemoglobin low\n\nRead by NILA on this phone.", followUp = LocalDate.of(2026, 10, 18)),
            HealthRecord("b", HealthKind.Visit, "Fever check-up", LocalDate.of(2026, 8, 2)),
        )
        val context = healthContext(records)
        assertTrue(context.lines().first().startsWith("2026-10-04 | Report | Blood test | Apollo | follow-up 2026-10-18"))
        assertFalse(context.contains("Read by NILA"))
        assertEquals("Wed 18 Dec 2024", dayLabelOf(LocalDate.of(2024, 12, 18)))
    }
}
