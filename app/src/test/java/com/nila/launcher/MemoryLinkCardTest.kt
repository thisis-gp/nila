package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryLinkCardTest {
    @Test
    fun aContactWithoutAPhotoShowsTheirInitial() {
        assertEquals("D", contactInitial("divya"))
        assertEquals("?", contactInitial(" "))
    }
}
