package com.nila.launcher

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyReminderTest {
    // Saturday 10 October 2026, 08:00.
    private val engine = NilaEngine(now = { LocalTime.of(8, 0) }, today = { LocalDate.of(2026, 10, 10) })

    @Test
    fun anElectricityBillReminderEveryMonth() {
        assertEquals(
            MonthlyReminder("Pay the electricity bill", 5, LocalTime.of(10, 0)),
            engine.respond("remind me to pay the electricity bill every month on the 5th"),
        )
        // "at 9 am" sets the time; "monthly" works as well as "every month".
        assertEquals(
            MonthlyReminder("Pay electricity bill", 21, LocalTime.of(9, 0)),
            engine.respond("set a monthly reminder to pay electricity bill on the 21st at 9 am"),
        )
    }

    @Test
    fun noDayMeansNilaAsks() {
        assertTrue((engine.respond("remind me to pay the electricity bill every month") as NilaResponse.Text).value.startsWith("Which day"))
        assertTrue((engine.respond("remind me every month on the 40th to pay rent") as NilaResponse.Text).value.startsWith("A month has"))
    }

    @Test
    fun oneOffRequestsAreNotMonthly() {
        assertNull(monthlyReminderArgs("set an alarm for 6:30"))
        assertNull(monthlyReminderArgs("what did I spend every month"))
        assertTrue((engine.route("set an alarm for 7 am") as NilaIntent.Call).call.capabilityId == "create_alarm")
    }

    @Test
    fun theCalendarRuleNeverSkipsAShortMonth() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=5", MonthlyReminder("Bill", 5, LocalTime.NOON).rule)
        // The 31st is "the last day": a 31st-only rule would skip 30-day months and February.
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", MonthlyReminder("Bill", 31, LocalTime.NOON).rule)
        assertEquals("the last day", dayLabel(MonthlyReminder("Bill", 30, LocalTime.NOON)))
        assertEquals("the 21st", dayLabel(MonthlyReminder("Bill", 21, LocalTime.NOON)))
        assertEquals("the 12th", dayLabel(MonthlyReminder("Bill", 12, LocalTime.NOON)))
    }

    @Test
    fun theFirstReminderIsTheNextOneAhead() {
        val now = LocalDateTime.of(2026, 10, 10, 8, 0)
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), firstReminder(MonthlyReminder("Bill", 5, LocalTime.of(10, 0)), now))
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0), firstReminder(MonthlyReminder("Bill", 21, LocalTime.of(9, 0)), now))
        assertEquals(LocalDateTime.of(2026, 10, 31, 10, 0), firstReminder(MonthlyReminder("Bill", 31, LocalTime.of(10, 0)), now))
    }
}
