package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchTest {
    @Test
    fun aDocumentIsFoundByTheNamesItsFileUsuallyHas() {
        val admit = fileSearch("find my admit card")!!
        assertEquals("admit card", admit.asked)
        assertTrue("hall_ticket" in admit.keywords && "admit" in admit.keywords)
        assertTrue("aadhar" in fileSearch("where is my aadhaar card?")!!.keywords)
        assertTrue("cv" in fileSearch("find my resume")!!.keywords)
        assertTrue("irctc" in fileSearch("find my train ticket")!!.keywords)
        // Any other file by its name.
        assertEquals("budget", fileSearch("find the file called budget")!!.asked)
    }

    @Test
    fun otherRequestsAreNotFileSearches() {
        assertNull(fileSearch("search for IPL score"))
        assertNull(fileSearch("get flight tickets to Delhi"))
        assertNull(fileSearch("show movie tickets"))
        assertNull(fileSearch("open google docs"))
        assertNull(fileSearch("open pdf reader"))
        assertNull(fileSearch("show my bills"))
        assertNull(fileSearch("get insurance plans"))
        assertNull(fileSearch("open passport app"))
        assertNull(fileSearch("find a gym near me"))
        assertNull(fileSearch("show my photos from yesterday"))
        assertNull(fileSearch("get me milk"))
        assertNull(fileSearch("open youtube"))
    }

    @Test
    fun sizesReadPlainly() {
        assertEquals("2.4 MB", sizeLabel(2_400_000))
        assertEquals("830 KB", sizeLabel(830_000))
    }

    @Test
    fun namesMatchWholeWordsAndSqlWildcardsAreEscaped() {
        assertTrue(fileNameMatches("PAN Card.pdf", listOf("pan")))
        assertFalse(fileNameMatches("Company report.pdf", listOf("pan")))
        assertFalse(fileNameMatches("download.pdf", listOf("dl")))
        assertEquals("%my\\_file\\%\\\\backup%", fileLikePattern("my_file%\\backup"))
    }
}
