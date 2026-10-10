package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest {
    @Test
    fun namesThePartOfDayFromTheHour() {
        assertEquals(PartOfDay.Night, partOfDay(4))
        assertEquals(PartOfDay.Morning, partOfDay(5))
        assertEquals(PartOfDay.Afternoon, partOfDay(12))
        assertEquals(PartOfDay.Evening, partOfDay(17))
        assertEquals(PartOfDay.Night, partOfDay(21))
        assertEquals(PartOfDay.Night, partOfDay(0))
    }
}
