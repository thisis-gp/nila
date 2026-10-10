package com.nila.launcher

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneSearchTest {
    // Sunday 11 October 2026.
    private val today = LocalDate.of(2026, 10, 11)
    private val dec18 = LocalDate.of(2024, 12, 18)

    @Test
    fun aPhotoRequestNamesTheDay() {
        assertEquals(PhotoSearch(dec18, dec18, "Wed 18 Dec 2024"), photoSearch("photos I took on Dec 18 2024", today))
        assertEquals(dec18, photoSearch("show pictures from 18th December 2024", today)!!.from)
        // A numeric date is day/month/year, as written in India.
        assertEquals(dec18, photoSearch("photos on 18/12/2024", today)!!.from)
        assertEquals(PhotoSearch(LocalDate.of(2024, 12, 1), LocalDate.of(2024, 12, 31), "December 2024"), photoSearch("my photos in december 2024", today))
        assertEquals(PhotoSearch(today.minusDays(1), today.minusDays(1), "yesterday"), photoSearch("pics from yesterday", today))
        assertEquals(LocalDate.of(2026, 10, 9), photoSearch("selfies from friday", today)!!.from)
    }

    @Test
    fun notAPhotoRequestOrNoDay() {
        assertNull(photoSearch("order biryani on Dec 18 2024", today))
        assertNull(photoSearch("show my photos", today))
        assertNull(photoSearch("photos from 31 February 2024", today))
    }
}
