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
        assertNull(timerSeconds("timer for 1193047 hours"))
        assertEquals(Timer(600), respond("set a timer for 10 minutes"))
        assertEquals("How long should the timer run? Try \"timer for 10 minutes\".",
            (engine.execute(NilaIntent.Call(ToolCall("set_timer", mapOf("hours" to 1193047)))) as NilaResponse.Text).value)
        // No length: NILA asks instead of guessing.
        assertTrue((respond("start a timer") as NilaResponse.Text).value.startsWith("How long"))
    }

    @Test
    fun timerControlsNeverStartANewTimer() {
        // "stop timer" used to fall into "start a timer" and ask "How long?".
        assertEquals(TimerControl("stop"), respond("stop the timer"))
        assertEquals(TimerControl("pause"), respond("pause timer"))
        assertEquals(TimerControl("resume"), respond("resume the timer"))
        assertEquals(TimerControl("status"), respond("how much time is left"))
        assertEquals(BatteryLevel, respond("how much battery is left"))
        assertEquals(Timer(300), respond("set a timer for 5 minutes"))
        assertEquals("9:05", timerText(545_000))
        assertEquals("1:02:30", timerText(3_750_000))
    }

    @Test
    fun alarmRemovalOnlyDismissesAnAlarmAndroidCanSee() {
        // Seen on the iQOO 15: asking vivo's Clock to dismiss 23:58 when only 06:30 existed removed the 6:30 alarms.
        assertEquals(DismissPlan.SHOW_LIST, dismissPlan(23, 58, 6, 30))
        assertEquals(DismissPlan.DISMISS_NEXT, dismissPlan(6, 30, 6, 30))
        assertEquals(DismissPlan.DISMISS_NEXT, dismissPlan(null, 0, 6, 30))
        assertEquals(DismissPlan.NOTHING_SET, dismissPlan(null, 0, null, null))
        assertEquals(DismissPlan.SHOW_LIST, dismissPlan(7, 0, null, null))
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
        // A selfie opens the front camera; "record a video" opens the recorder (vivo showed photo mode before).
        assertEquals(Camera(video = false, front = true), respond("take a selfie"))
        assertEquals(Camera(video = true, front = false), respond("record a video"))
        assertEquals(Camera(video = true, front = true), respond("record a video on the front camera"))
        assertEquals(VoiceRecorder, respond("open the voice recorder"))
        // The user's voice is recorded on the video camera, never a voice recorder app.
        assertEquals(Camera(video = true, front = false), respond("record my voice"))
        assertEquals(Camera(video = true, front = false), respond("record my voice on the video camera"))
        assertEquals(Camera(video = true, front = false), respond("take a voice note"))
        assertEquals(Camera(video = true, front = false), respond("record some audio"))
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
