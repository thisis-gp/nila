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

    @Test
    fun followUpTurnsAreNilasOwnLabelsNotRequests() {
        assertTrue(isFollowUpTurn("Zepto"))
        assertTrue(isFollowUpTurn("Action result"))
        assertFalse(isFollowUpTurn("Set an alarm for 6:30 am"))
    }

    @Test
    fun cheapestLineSurvivesPercentInQueryAndPack() {
        val milk = PickableProduct("Toned milk", "1 L · 3% fat", "₹54") {}
        val line = cheapestLine("toned milk 3%", listOf(ProductProviderGroup("Zepto", "from ₹54", listOf(milk))))
        assertTrue(line, line.contains("₹54 per litre"))
    }
}
