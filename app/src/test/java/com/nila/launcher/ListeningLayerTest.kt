package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningLayerTest {
    private fun highlighted(request: String) = requestKeyPhrase(request)?.let(request::substring)

    @Test
    fun highlightsWhatTheRequestIsAbout() {
        assertEquals("light dinner", highlighted("Find a light dinner for three under ₹800"))
        assertEquals("alarm", highlighted("Set an alarm for 6:30 am"))
        assertEquals("YouTube", highlighted("Open YouTube."))
        assertEquals("biryani", highlighted("Please order me some biryani near Indiranagar"))
    }

    @Test
    fun highlightsNothingWithoutAnAction() {
        assertNull(highlighted("What is 18% of 4250?"))
        assertNull(highlighted("Find"))
        assertNull(highlighted(""))
    }

    @Test
    fun labelFollowsTheVoiceState() {
        assertEquals("LISTENING", listeningLabel(VoiceState.LISTENING, heard = null))
        assertEquals("UNDERSTANDING", listeningLabel(VoiceState.UNDERSTANDING, heard = null))
        assertEquals("HEARD YOU", listeningLabel(VoiceState.IDLE, heard = "Order biryani"))
        assertEquals("READY", listeningLabel(VoiceState.IDLE, heard = null))
    }
}
