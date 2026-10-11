package com.nila.launcher

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NilaRemindersTest {
    private val now = LocalDateTime.of(2026, 10, 11, 12, 0)

    @Test
    fun aMonthlyReminderFiresEveryMonth() {
        val bill = NilaReminder("b", "Pay the electricity bill", "", LocalDateTime.of(2026, 11, 5, 10, 0), monthlyDay = 5)
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), nextFiring(bill, now))
        // Right after it fired on 5 November, the next one is 5 December.
        assertEquals(LocalDateTime.of(2026, 12, 5, 10, 0), nextFiring(bill, LocalDateTime.of(2026, 11, 5, 10, 1)))
        // The 31st means the last day, so February is not skipped.
        val rent = bill.copy(monthlyDay = 31)
        assertEquals(LocalDateTime.of(2027, 2, 28, 10, 0), nextFiring(rent, LocalDateTime.of(2027, 2, 1, 0, 0)))
        // The 30th is the 30th in a 31-day month, not the 31st.
        assertEquals(LocalDateTime.of(2027, 5, 30, 10, 0), nextFiring(bill.copy(monthlyDay = 30), LocalDateTime.of(2027, 5, 1, 0, 0)))
    }

    @Test
    fun aReminderDueWhileThePhoneWasOffIsMissed() {
        val bill = NilaReminder("b", "Pay the electricity bill", "", LocalDateTime.of(2026, 11, 5, 10, 0), monthlyDay = 5, due = LocalDateTime.of(2026, 11, 5, 10, 0))
        assertEquals(true, missed(bill, LocalDateTime.of(2026, 11, 5, 18, 0)))
        assertEquals(false, missed(bill, LocalDateTime.of(2026, 11, 5, 9, 0)))
        assertEquals(false, missed(bill.copy(due = null), LocalDateTime.of(2026, 11, 5, 18, 0)))
    }

    @Test
    fun aOneOffReminderFiresOnceThenIsDone() {
        val followUp = NilaReminder("f", "Follow-up", "", LocalDateTime.of(2026, 10, 18, 9, 0))
        assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), nextFiring(followUp, now))
        assertNull(nextFiring(followUp, LocalDateTime.of(2026, 10, 18, 9, 1)))
    }

    @Test
    fun aReportsFollowUpBecomesAReminder() {
        val record = HealthRecord("r1", HealthKind.Report, "Blood test", LocalDate.of(2026, 10, 4), place = "Apollo Hospital", followUp = LocalDate.of(2026, 10, 18))
        val reminder = followUpReminder(record)!!
        assertEquals("followup-r1", reminder.id)
        assertEquals("Follow-up: Blood test", reminder.title)
        assertEquals(LocalDateTime.of(2026, 10, 18, 9, 0), reminder.at)
        assertNull(followUpReminder(record.copy(followUp = null)))
    }

    @Test
    fun aMonthlyRequestFromChatStartsAtTheNextDay() {
        val reminder = monthlyReminderOf(MonthlyReminder("Pay the electricity bill", 5, LocalTime.of(10, 0)), now)
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), reminder.at)
        assertEquals(5, reminder.monthlyDay)
    }
}
