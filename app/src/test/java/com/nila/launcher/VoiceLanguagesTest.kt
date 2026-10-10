package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceLanguagesTest {
    @Test
    fun speechInputPrefersInstalledThenPendingThenDownloadableThenOnline() {
        assertEquals(SpeechInputStatus.ON_DEVICE, speechInputStatus("hi-IN", listOf("hi-IN"), listOf("hi-IN"), listOf("hi-IN"), listOf("hi-IN")))
        assertEquals(SpeechInputStatus.DOWNLOADING, speechInputStatus("hi-IN", emptyList(), listOf("hi-IN"), listOf("hi-IN"), emptyList()))
        assertEquals(SpeechInputStatus.DOWNLOADABLE, speechInputStatus("ml-IN", listOf("en-IN"), emptyList(), listOf("ml_in"), emptyList()))
        assertEquals(SpeechInputStatus.ONLINE_ONLY, speechInputStatus("ml-IN", emptyList(), emptyList(), emptyList(), listOf("ml-IN")))
        assertEquals(SpeechInputStatus.UNAVAILABLE, speechInputStatus("ml-IN", listOf("en-IN"), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun speechOutputMatchesLanguageAndCountryAndPrefersOffline() {
        val hindiOnline = TtsVoice(Locale.forLanguageTag("hi-IN"), needsNetwork = true, notInstalled = false)
        val hindiOffline = TtsVoice(Locale.forLanguageTag("hi-IN"), needsNetwork = false, notInstalled = false)
        val malayalamMissing = TtsVoice(Locale.forLanguageTag("ml-IN"), needsNetwork = false, notInstalled = true)
        val englishUs = TtsVoice(Locale.forLanguageTag("en-US"), needsNetwork = false, notInstalled = false)

        assertEquals(SpeechOutputStatus.OFFLINE, speechOutputStatus("hi-IN", listOf(hindiOnline, hindiOffline)))
        assertEquals(SpeechOutputStatus.ONLINE_ONLY, speechOutputStatus("hi-IN", listOf(hindiOnline)))
        assertEquals(SpeechOutputStatus.NEEDS_DOWNLOAD, speechOutputStatus("ml-IN", listOf(malayalamMissing)))
        // An en-US voice does not count as English (India).
        assertEquals(SpeechOutputStatus.UNAVAILABLE, speechOutputStatus("en-IN", listOf(englishUs)))
    }

    @Test
    fun voiceIsReadyOnlyWhenBothDirectionsAreOffline() {
        val hindi = voiceLanguages[1]
        assertTrue(LanguageSupport(hindi, SpeechInputStatus.ON_DEVICE, SpeechOutputStatus.OFFLINE).voiceReady)
        assertFalse(LanguageSupport(hindi, SpeechInputStatus.ON_DEVICE, SpeechOutputStatus.ONLINE_ONLY).voiceReady)
        assertFalse(LanguageSupport(hindi, SpeechInputStatus.ONLINE_ONLY, SpeechOutputStatus.OFFLINE).voiceReady)
    }
}
