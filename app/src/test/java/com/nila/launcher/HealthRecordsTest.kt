package com.nila.launcher

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Seen on the iQOO 15: a question about a test is answered, not filed as a new visit.
        assertFalse(isHealthMention("when was my last blood test"))
        assertTrue(isHealthQuestion("when was my last blood test"))
        assertTrue(isHealthQuestion("what did the doctor say about my BP?"))
        assertFalse(isHealthQuestion("how do I book a doctor"))
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

    @Test
    fun aFailedWriteHasNoSuccessfulResult() {
        val record = HealthRecord("a", HealthKind.Report, "Blood test", today)
        var attempted = false
        assertNull(committedRecords(listOf(record)) { attempted = true; false })
        assertTrue(attempted)
    }

    @Test
    fun aReportPhotoIsReadIntoARecord() {
        val reply = """Here you go:
            {"kind": "Report", "title": "Blood test", "date": "2026-10-04", "place": "Apollo Hospital",
             "findings": [{"name": "Haemoglobin", "value": "11.2", "unit": "g/dL", "flag": "low"},
                          {"name": "Glucose", "value": "92", "unit": "mg/dL", "flag": "normal"},
                          {"name": "", "value": "5"}],
             "follow_up": "18 Oct 2026", "summary": "Haemoglobin is slightly low."}"""
        val reading = parseReportReading(reply)!!
        assertEquals("Blood test", reading.title)
        assertEquals(LocalDate.of(2026, 10, 4), reading.date)
        // A finding without a name is dropped, never shown as a bare number.
        assertEquals(listOf("Haemoglobin", "Glucose"), reading.findings.map { it.name })
        assertEquals(LocalDate.of(2026, 10, 18), reading.followUp)

        val record = recordFrom(reading, "a.img", fallbackDate = today, id = "r1")
        assertEquals(LocalDate.of(2026, 10, 4), record.date)
        assertEquals("Apollo Hospital", record.place)
        assertEquals(LocalDate.of(2026, 10, 18), record.followUp)
        assertEquals("Haemoglobin is slightly low.", record.notes)
        assertEquals(ReportFinding("Haemoglobin", "11.2", "g/dL", "low"), record.findings[0])
        assertEquals(listOf("a.img"), record.files)
    }

    @Test
    fun anUnreadableReportIsStillSavedWithoutMadeUpValues() {
        assertEquals(null, parseReportReading("Sorry, I can't read this image."))
        val record = recordFrom(null, "b.img", fallbackDate = today.minusWeeks(1), id = "r2")
        assertEquals(today.minusWeeks(1), record.date)
        assertTrue(record.notes.startsWith("NILA couldn't read this report"))
        assertEquals(null, record.followUp)
    }

    @Test
    fun aSavedReportKeepsItsFollowUpAndUnverifiedMark() {
        val reading = parseReportReading("""{"kind":"Report","title":"CBC","date":"2026-10-04","findings":[{"name":"Haemoglobin","value":"11.2"}],"follow_up":"18 Oct 2026"}""")!!
        val record = recordFrom(reading, "a.pdf", fallbackDate = today, id = "r4", pages = 3)
        assertTrue(record.unverified)
        assertTrue(record.notes.contains("NILA read page 1 of 3"))
        assertEquals(listOf(record), decodeRecords(encodeRecords(listOf(record))))
        assertEquals(LocalDate.of(2026, 10, 18), decodeRecords(encodeRecords(listOf(record))).single().followUp)
        // When the reader can't load, the record says so instead of "couldn't read".
        assertTrue(recordFrom(null, "b.img", today, unavailable = true).notes.contains("isn't available"))
    }

    @Test
    fun aReportWithoutAPrintedDateTakesTheDayTheUserSaid() {
        val reading = parseReportReading("""{"kind":"Visit","title":"","date":"","place":"","findings":[],"follow_up":"","summary":""}""")!!
        val record = recordFrom(reading, "c.img", fallbackDate = today.minusWeeks(1), id = "r3")
        assertEquals(today.minusWeeks(1), record.date)
        assertEquals("Hospital visit", record.title)
        assertEquals(null, record.followUp)
    }

    @Test
    fun followUpDatesAreReadInEveryPrintedFormat() {
        val expected = LocalDate.of(2027, 1, 4)
        listOf("04 Jan 2027", "4 January 2027", "Follow-up: 04 Jan 2027", "04/01/2027", "04-01-2027", "04.01.2027", "2027-01-04", "Jan 4, 2027", "4th Jan, 2027")
            .forEach { assertEquals(it, expected, parseReportDate(it)) }
    }

    @Test
    fun aDateIsNeverInvented() {
        listOf("", "04", "Jan 2027", "after 3 months", "31/02/2027", "04 Foo 2027").forEach { assertEquals(it, null, parseReportDate(it)) }
    }

    @Test
    fun flagsSummaryCountsHighAndLow() {
        val f = { flag: String -> ReportFinding("x", "1", flag = flag) }
        assertEquals("2 high · 1 low", flagsSummary(listOf(f("high"), f("low"), f("high"), f("normal"))))
        assertEquals("1 low", flagsSummary(listOf(f("low"), f(""))))
        assertEquals("", flagsSummary(listOf(f("normal"))))
    }

    @Test
    fun anotherPageAddsItsValuesWithoutRepeats() {
        val first = ReportFinding("Haemoglobin", "11.2", "g/dL", "low")
        val record = HealthRecord("r", HealthKind.Report, "Blood test", today, files = listOf("a.img"), findings = listOf(first))
        val page = ReportReading(HealthKind.Report, "", null, "City Lab", listOf(first, ReportFinding("Glucose", "92")), LocalDate.of(2027, 1, 4), "")
        val merged = withPage(record, page, "b.img")
        assertEquals(listOf("a.img", "b.img"), merged.files)
        assertEquals(listOf("Haemoglobin", "Glucose"), merged.findings.map { it.name })
        assertEquals(LocalDate.of(2027, 1, 4), merged.followUp)
        assertEquals("City Lab", merged.place)
    }
}
