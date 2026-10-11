package com.nila.launcher

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGlanceTest {
    private val now = LocalDateTime.of(2026, 10, 11, 10, 8)
    private fun event(at: LocalDateTime, title: String = "Standup") = GlanceEvent(7, title, at)
    private fun reminder(at: LocalDateTime) = NilaReminder("r", "Pay rent", "", at)
    private fun lines(data: GlanceData, clock: ClockStyle = ClockStyle.Hours24) = glanceLines(data, now, clock)

    @Test
    fun noDataShowsNothing() = assertTrue(lines(GlanceData()).isEmpty())

    @Test
    fun eventShowsTimeAndCountdownUnderThreeHours() {
        val line = lines(GlanceData(events = listOf(event(now.withHour(11).withMinute(30))))).single()
        assertEquals("Standup at 11:30 · in 1 h 22 m", line.text)
        assertEquals(GlanceIcon.Calendar, line.icon)
    }

    @Test
    fun eventBeyondThreeHoursHasNoCountdownAndBeyondTwelveIsHidden() {
        assertEquals("Standup at 16:00", lines(GlanceData(events = listOf(event(now.withHour(16).withMinute(0))))).single().text)
        assertTrue(lines(GlanceData(events = listOf(event(now.plusHours(13))))).isEmpty())
        assertTrue(lines(GlanceData(events = listOf(event(now.minusMinutes(5))))).isEmpty())
    }

    @Test
    fun countdownWording() {
        assertEquals("in 45 m", countdown(45))
        assertEquals("in 2 h", countdown(120))
        assertEquals("now", countdown(0))
    }

    @Test
    fun twelveHourClockHasNoAmPm() {
        val alarm = now.plusDays(1).withHour(6).withMinute(30)
        assertEquals("Alarm 6:30 tomorrow", lines(GlanceData(alarm = alarm), ClockStyle.Hours12).single().text)
    }

    @Test
    fun alarmTodayHasNoDayWord() {
        assertEquals("Alarm 19:00", lines(GlanceData(alarm = now.withHour(19).withMinute(0))).single().text)
    }

    @Test
    fun alarmBeyondADayIsHidden() = assertTrue(lines(GlanceData(alarm = now.plusHours(25))).isEmpty())

    @Test
    fun reminderTodayOrTomorrowOnly() {
        assertEquals("Pay rent at 18:00", lines(GlanceData(reminders = listOf(reminder(now.withHour(18).withMinute(0))))).single().text)
        assertEquals("Pay rent tomorrow at 09:00", lines(GlanceData(reminders = listOf(reminder(now.plusDays(1).withHour(9).withMinute(0))))).single().text)
        assertTrue(lines(GlanceData(reminders = listOf(reminder(now.plusDays(2))))).isEmpty())
    }

    @Test
    fun stepsAreFormattedAndZeroIsHidden() {
        assertEquals("4,210 steps today", lines(GlanceData(steps = 4210)).single().text)
        assertTrue(lines(GlanceData(steps = 0)).isEmpty())
    }

    @Test
    fun priorityOrderAndTwoLineCap() {
        val data = GlanceData(
            events = listOf(event(now.plusHours(1))),
            reminders = listOf(reminder(now.plusHours(2))),
            alarm = now.plusHours(3),
            steps = 5000,
        )
        assertEquals(listOf(GlanceIcon.Calendar, GlanceIcon.Reminder), lines(data).map { it.icon })
        assertEquals(listOf(GlanceIcon.Alarm, GlanceIcon.Steps), lines(data.copy(events = emptyList(), reminders = emptyList())).map { it.icon })
        assertEquals(listOf(GlanceIcon.Reminder, GlanceIcon.Alarm), lines(data.copy(events = emptyList())).map { it.icon })
    }
}
