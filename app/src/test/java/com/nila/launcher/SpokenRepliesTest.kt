package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenRepliesTest {
    @Test
    fun voiceLanguageFollowsTheReplysScript() {
        assertEquals("ml-IN", replyLanguage("എനിക്ക് സുഖമില്ല"))
        assertEquals("hi-IN", replyLanguage("मुझे भूख लगी है"))
        assertEquals("ta-IN", replyLanguage("எனக்கு பசிக்கிறது"))
        assertEquals("te-IN", replyLanguage("నాకు ఆకలిగా ఉంది"))
        assertEquals("kn-IN", replyLanguage("ನನಗೆ ಹಸಿವಾಗಿದೆ"))
        assertEquals("th-TH", replyLanguage("สวัสดี"))
        assertEquals("ja-JP", replyLanguage("こんにちは"))
        assertEquals("ko-KR", replyLanguage("안녕하세요"))
        assertEquals("el-GR", replyLanguage("Καλημέρα"))
        assertEquals("he-IL", replyLanguage("שלום"))
        assertEquals("en-IN", replyLanguage("Hello! I'm NILA."))
        // Mixed text with any Malayalam is read in Malayalam.
        assertEquals("ml-IN", replyLanguage("Alarm 6:30 സെറ്റ് ചെയ്തു"))
    }

    @Test
    fun cardsAreSpokenAsPromptsNeverAsDoneActions() {
        val alarm = spokenText(NilaResponse.Alarm(18, 30))!!
        assertTrue(alarm.startsWith("Alarm for 18:30 is ready"))
        assertTrue("Tap Set alarm" in alarm)
        assertFalse(claimsAction(alarm))

        val confirm = spokenText(NilaResponse.NeedsConfirmation("t", ToolCall("x", emptyMap()), CapabilityDescriptor("x", "x", emptyList(), PermissionClass.COMMIT), emptyList()))!!
        assertTrue("Nothing happens until you do" in confirm)
        assertFalse(claimsAction(confirm))
    }

    @Test
    fun textRepliesAreSpokenAsIsAndBlankOnesAreSkipped() {
        assertEquals("Hello!", spokenText(NilaResponse.Text("Hello!")))
        assertEquals(null, spokenText(NilaResponse.Text("  ")))
    }
}
