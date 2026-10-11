package com.nila.launcher

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneOffReminderTest {
    // Sunday 11 October 2026, 10:00.
    private val now = LocalDateTime.of(2026, 10, 11, 10, 0)

    @Test
    fun aReminderWithWhatAndWhen() {
        assertEquals(OneOffReminder("Call amma", LocalDateTime.of(2026, 10, 11, 18, 0)), oneOffReminder("remind me to call amma at 6 pm", now))
        assertEquals(OneOffReminder("Pay rent", LocalDateTime.of(2026, 10, 12, 9, 0)), oneOffReminder("set a reminder to pay rent tomorrow", now))
        assertEquals(OneOffReminder("Take medicine", LocalDateTime.of(2026, 10, 11, 12, 0)), oneOffReminder("remind me in 2 hours to take medicine", now))
        assertEquals(OneOffReminder("Water the plants", LocalDateTime.of(2026, 10, 11, 21, 0)), oneOffReminder("remind me to water the plants tonight", now))
        // "at 6" with no am/pm at 10:00 means this evening.
        assertEquals(LocalDateTime.of(2026, 10, 11, 18, 0), (oneOffReminder("remind me at 6 to go for a walk", now) as OneOffReminder).at)
        // 9 am has passed today, so "at 9 am" is tomorrow.
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), (oneOffReminder("reminder at 9 am to call the bank", now) as OneOffReminder).at)
    }

    @Test
    fun nilaAsksForWhatIsMissing() {
        assertTrue((oneOffReminder("help me to set a reminder", now) as NilaResponse.Text).value.startsWith("Sure. What should I remind you about, and when"))
        assertTrue((oneOffReminder("remind me to call amma", now) as NilaResponse.Text).value.startsWith("When should I remind you to call amma"))
        assertTrue((oneOffReminder("remind me at 6 pm", now) as NilaResponse.Text).value.startsWith("What should I remind you about"))
    }

    @Test
    fun notAOneTimeReminder() {
        assertNull(oneOffReminder("remind me to pay the electricity bill every month on the 5th", now))
        assertNull(oneOffReminder("set an alarm for 6 am", now))
        assertNull(oneOffReminder("what reminders do I have", now))
        assertNull(oneOffReminder("cancel my reminder", now))
    }
}
