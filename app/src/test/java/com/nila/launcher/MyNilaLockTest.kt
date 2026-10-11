package com.nila.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyNilaLockTest {
    private val minute = 60_000L

    @Test
    fun neverUnlockedHasNothingToExpire() {
        assertFalse(lockExpired(unlockedAt = null, now = 10 * minute, screenOffAt = 5 * minute, backgroundSince = 0L))
    }

    @Test
    fun stillOpenInTheForegroundHolds() {
        assertFalse(lockExpired(unlockedAt = 0L, now = 30 * minute, screenOffAt = null, backgroundSince = null))
    }

    @Test
    fun aScreenOffAfterTheUnlockExpiresIt() {
        assertTrue(lockExpired(unlockedAt = 0L, now = minute, screenOffAt = 30_000L, backgroundSince = null))
    }

    @Test
    fun aScreenOffBeforeTheUnlockDoesNot() {
        assertFalse(lockExpired(unlockedAt = minute, now = 2 * minute, screenOffAt = 30_000L, backgroundSince = null))
    }

    @Test
    fun backgroundUnderFiveMinutesHolds() {
        assertFalse(lockExpired(unlockedAt = 0L, now = 6 * minute, screenOffAt = null, backgroundSince = 2 * minute))
    }

    @Test
    fun backgroundForFiveMinutesExpires() {
        assertTrue(lockExpired(unlockedAt = 0L, now = 7 * minute, screenOffAt = null, backgroundSince = 2 * minute))
    }
}
