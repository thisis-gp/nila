package com.nila.launcher

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTest {
    @Test
    fun wavHeaderDescribes16kMono16BitPcm() {
        val pcm = ByteArray(3200) { it.toByte() }
        val bytes = wav(pcm)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(1.toShort(), header.getShort(20)) // PCM
        assertEquals(1.toShort(), header.getShort(22)) // mono
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28)) // byte rate
        assertEquals(16.toShort(), header.getShort(34)) // bits per sample
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(44 + pcm.size, bytes.size)
    }

    @Test
    fun rmsMeasuresLoudness() {
        assertEquals(0.0, rms(ShortArray(160)), 0.0)
        assertEquals(1000.0, rms(ShortArray(160) { if (it % 2 == 0) 1000 else -1000 }), 0.001)
        assertEquals(0.0, rms(ShortArray(0)), 0.0)
    }

    @Test
    fun recordingStopsAfterAPauseOnlyOnceSomeoneSpoke() {
        val end = EndOfSpeech(threshold = 500.0, quietMs = 300, waitMs = 1_000)
        assertFalse(end.update(100.0, 100)) // silence before speaking does not stop
        assertFalse(end.update(800.0, 100)) // speech
        assertFalse(end.update(100.0, 100))
        assertFalse(end.update(100.0, 100))
        assertTrue(end.update(100.0, 100)) // 300 ms of quiet after speech
        assertTrue(end.heardSpeech)
    }

    @Test
    fun recordingGivesUpWhenNobodySpeaks() {
        val end = EndOfSpeech(threshold = 500.0, quietMs = 300, waitMs = 500)
        repeat(4) { assertFalse(end.update(50.0, 100)) }
        assertTrue(end.update(50.0, 100))
        assertFalse(end.heardSpeech)
    }

    @Test
    fun autoPromptLetsGemmaDetectAndKeepEachLanguagesScript() {
        val prompt = transcriptionPrompt(null)
        assertTrue("English, Hindi or Malayalam" in prompt && "may mix" in prompt)
        assertTrue("Malayalam script" in prompt && "Devanagari" in prompt && "Output only the transcript" in prompt)
    }

    @Test
    fun transcriptionPromptAsksForTheTranscriptInTheChosenLanguage() {
        val prompt = transcriptionPrompt("മലയാളം · Malayalam")
        assertTrue("Malayalam" in prompt && "own script" in prompt && "Output only the transcript" in prompt)
    }
}
