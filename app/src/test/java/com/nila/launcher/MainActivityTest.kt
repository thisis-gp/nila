package com.nila.launcher

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    @Test
    fun tapOnceFiresOnlyOnce() {
        val consumed = AtomicBoolean(false)
        assertTrue(tapOnce(consumed))
        assertFalse(tapOnce(consumed))
        assertFalse(tapOnce(consumed))
    }

    @Test
    fun tapOnceAllowsOnlyOneWinnerUnderConcurrentTaps() {
        val consumed = AtomicBoolean(false)
        val winners = (1..50).toList().parallelStream().filter { tapOnce(consumed) }.count()
        assertTrue("expected exactly one winner, got $winners", winners == 1L)
    }
}
