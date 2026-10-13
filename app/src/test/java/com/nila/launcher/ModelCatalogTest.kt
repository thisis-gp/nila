package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun listsKnownModelsAsInstalledOrMissing() {
        val choices = ModelCatalog.choices(
            installedFiles = setOf("gemma3-1b-it-int4.litertlm"),
            availableMemoryMiB = 2048,
        )

        assertTrue(choices.first { it.model.id == "gemma3-1b" }.installed)
        assertFalse(choices.first { it.model.id == "gemma4-e2b" }.installed)
    }

    @Test
    fun selectsPreferredInstalledModelWhenMemoryAllows() {
        val options = ModelCatalog.choices(
            installedFiles = setOf("gemma3-1b-it-int4.litertlm", "nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm"),
            availableMemoryMiB = 4096,
        )

        assertEquals("jimi-gemma3-1b", ModelCatalog.select(options, "jimi-gemma3-1b")?.id)
    }

    @Test
    fun fallsBackToSmallestSafeInstalledModel() {
        val options = ModelCatalog.choices(
            installedFiles = setOf("gemma3-1b-it-int4.litertlm", "nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm"),
            availableMemoryMiB = 1200,
        )

        assertEquals("gemma3-1b", ModelCatalog.select(options, "jimi-gemma3-1b")?.id)
    }

    @Test
    fun doesNotSelectModelWhenNoneFitCurrentMemory() {
        val options = ModelCatalog.choices(
            installedFiles = setOf("gemma3-1b-it-int4.litertlm"),
            availableMemoryMiB = 100,
        )

        assertFalse(options.first { it.model.id == "gemma3-1b" }.fitsMemory)
        assertNull(ModelCatalog.select(options, "gemma3-1b"))
    }
}
