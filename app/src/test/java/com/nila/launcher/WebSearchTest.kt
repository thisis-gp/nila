package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchTest {
    @Test
    fun onlyARealLookingKeyIsSaved() {
        assertEquals("tvly-dev-abc12345XYZ", tavilyKey("  tvly-dev-abc12345XYZ \n"))
        // A typo or a sentence must not show web search as connected.
        assertNull(tavilyKey("tvly-"))
        assertNull(tavilyKey("my key is tvly-abc12345"))
        assertNull(tavilyKey("tvly-abc 12345"))
        assertNull(tavilyKey(""))
    }
}
