package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class VoiceLanguagesTest {
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
    fun reportedLanguagesComeOnlyFromTheEngineVoiceList() {
        val tamilOffline = TtsVoice(Locale.forLanguageTag("ta-IN"), needsNetwork = false, notInstalled = false)
        val tamilDownload = TtsVoice(Locale.forLanguageTag("ta-IN"), needsNetwork = false, notInstalled = true)
        val output = availableSpeechOutput(listOf(tamilOffline, tamilDownload))

        assertEquals(mapOf("ta-IN" to SpeechOutputStatus.OFFLINE), output)
        assertEquals(emptyMap<String, SpeechOutputStatus>(), availableSpeechOutput(emptyList()))
    }

}
