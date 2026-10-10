package com.nila.launcher

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionsTest {
    // Saturday 10 October 2026, 08:00.
    private val saturday = LocalDate.of(2026, 10, 10)
    private val engine = NilaEngine(now = { LocalTime.of(8, 0) }, today = { saturday })

    private fun respond(text: String) = engine.respond(text)

    @Test
    fun timers() {
        assertEquals(600, timerSeconds("set a timer for 10 minutes"))
        assertEquals(5400, timerSeconds("timer 1 hour 30 mins"))
        assertEquals(90, timerSeconds("90 seconds timer"))
        assertNull(timerSeconds("start a timer"))
        assertEquals(Timer(600), respond("set a timer for 10 minutes"))
        // No length: NILA asks instead of guessing.
        assertTrue((respond("start a timer") as NilaResponse.Text).value.startsWith("How long"))
    }

    @Test
    fun flashlight() {
        assertEquals(Torch(true), respond("turn on the flashlight"))
        assertEquals(Torch(false), respond("switch off the torch"))
        assertEquals(Torch(true), respond("flash light please"))
    }

    @Test
    fun camera() {
        assertEquals(Camera(false), respond("open the camera"))
        assertEquals(Camera(false), respond("take a selfie"))
        assertEquals(Camera(true), respond("record a video"))
    }

    @Test
    fun notesKeepTheUsersWords() {
        assertEquals(Note("The gate code is 4521"), respond("Take a note that The gate code is 4521"))
        assertEquals(Note("buy milk"), respond("note down buy milk"))
        assertEquals("pick up keys", noteText("make a note: pick up keys"))
        // Nothing to save: NILA asks for the text.
        assertTrue(respond("take a note") is NilaResponse.Text)
    }

    @Test
    fun calls() {
        assertEquals(Call("Mom"), respond("call Mom"))
        assertEquals(Call("98450 12345"), respond("dial 98450 12345"))
        assertEquals("9845012345", phoneNumber("98450 12345"))
        assertNull(phoneNumber("Mom"))
        assertNull(callTarget("what is a phone call"))
    }

    @Test
    fun calendarEventsReadTitleDayAndTime() {
        assertEquals(
            CalendarEvent("Meeting with arjun", saturday.plusDays(1), LocalTime.of(15, 0)),
            respond("schedule a meeting with Arjun tomorrow at 3 pm"),
        )
        // No am/pm: office hours, so "at 3" is the afternoon. A weekday means the next one.
        assertEquals(
            CalendarEvent("Dentist appointment", LocalDate.of(2026, 10, 12), LocalTime.of(15, 30)),
            respond("add a dentist appointment on monday at 3:30"),
        )
        // No time: an all-day event today.
        assertEquals(CalendarEvent("Event", saturday, null), respond("add an event to my calendar"))
        assertEquals("That event time is not valid.", (respond("schedule a meeting at 5:99") as NilaResponse.Text).value)
    }

    @Test
    fun eventHelpers() {
        assertEquals(15, eventHour(3, null))
        assertEquals(9, eventHour(9, null))
        assertEquals(21, eventHour(9, "pm"))
        assertEquals(saturday.plusDays(1), eventDate("tomorrow", saturday))
        // "saturday" on a Saturday means next week, never today.
        assertEquals(saturday.plusDays(7), eventDate("saturday", saturday))
        assertEquals("Sat 10 Oct, all day", eventWhen(saturday, null))
        assertEquals("Sun 11 Oct, 15:00", eventWhen(saturday.plusDays(1), LocalTime.of(15, 0)))
    }

    @Test
    fun existingRoutesStillWin() {
        assertTrue((engine.route("set an alarm for 7 am") as NilaIntent.Call).call.capabilityId == "create_alarm")
        assertTrue((engine.route("cancel my alarm") as NilaIntent.Call).call.capabilityId == "remove_alarm")
        assertFalse(engine.route("open youtube").let { it is NilaIntent.Call && it.call.capabilityId == "open_camera" })
    }
}
