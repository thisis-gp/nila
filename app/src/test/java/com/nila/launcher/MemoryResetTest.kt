package com.nila.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryResetTest {
    @Test
    fun onlyAWrongKeyWipesMemory() {
        assertTrue(isKeyFailure(RuntimeException("file is not a database: , while compiling: select count(*) from sqlite_master")))
        assertTrue(isKeyFailure(IllegalStateException("open failed", RuntimeException("File is not a database"))))
    }

    @Test
    fun aMigrationMistakeIsNotAWrongKey() {
        assertFalse(isKeyFailure(IllegalStateException("Migration didn't properly handle: people")))
        assertFalse(isKeyFailure(RuntimeException("Room cannot verify the data integrity")))
    }
}
