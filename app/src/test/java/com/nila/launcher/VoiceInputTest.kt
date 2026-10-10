package com.nila.launcher

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTest {
    @Test
    fun chipCyclesThroughEveryLanguageAndWraps() {
        assertEquals("hi-IN", nextVoiceLanguage("en-IN"))
        assertEquals("ml-IN", nextVoiceLanguage("hi-IN"))
        assertEquals("en-IN", nextVoiceLanguage("ml-IN"))
        assertEquals(listOf("EN", "हि", "മ"), voiceLanguages.map { voiceChipLabel(it.tag) })
    }

    @Test
    fun missingPackPointsToDownloadOnlyWhenOneExists() {
        val downloadable = voiceErrorMessage(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, "English (India)", SpeechInputStatus.DOWNLOADABLE)
        assertTrue(downloadable.startsWith("English (India) speech isn't installed"))
        assertTrue("Voice languages" in downloadable && "type" in downloadable)

        // The iQOO 15 has no Malayalam recognizer at all, so there is nothing to download.
        val unavailable = voiceErrorMessage(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, "മലയാളം · Malayalam", SpeechInputStatus.UNAVAILABLE)
        assertEquals("മലയാളം · Malayalam speech isn't available on this phone yet. Type instead.", unavailable)
    }

    @Test
    fun everyErrorStillOffersTyping() {
        listOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_CLIENT,
        ).forEach { error ->
            val message = voiceErrorMessage(error, "Hindi")
            assertTrue(message, message.contains("type", ignoreCase = true) || message.contains("try again", ignoreCase = true))
        }
    }
}
