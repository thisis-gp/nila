package com.nila.launcher.skills

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAppStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private var nextId = 0
    private fun store() = FileAppStore(folder.root) { "id${++nextId}" }

    @Test
    fun addListUpdateDeleteRoundTrip() {
        val store = store()
        val book = store.add("reading-log-storage", mapOf("name" to JsonPrimitive("Dune"), "pages" to JsonPrimitive(412)))
        assertEquals(listOf(book), store.list("reading-log-storage"))
        assertEquals(412.0, book.number("pages"))

        val renamed = store.update("reading-log-storage", book.id, mapOf("name" to JsonPrimitive("Dune Messiah")))
        assertEquals("Dune Messiah", renamed?.string("name"))
        assertEquals(412.0, renamed?.number("pages"))
        assertEquals(listOf(renamed), store.list("reading-log-storage"))

        assertTrue(store.delete("reading-log-storage", book.id))
        assertFalse(store.delete("reading-log-storage", book.id))
        assertNull(store.update("reading-log-storage", book.id, emptyMap()))
        assertEquals(emptyList<Item>(), store.list("reading-log-storage"))
    }

    @Test
    fun rejectsStoreNamesThatLeaveTheAppFolder() {
        for (name in listOf("../x", "a/b", "", "A", "a\b")) {
            val rejected = runCatching { store().list(name) }.exceptionOrNull()
            assertTrue("$name should be rejected", rejected is IllegalArgumentException)
        }
        assertEquals(emptyList<String>(), folder.root.parentFile.list()!!.filter { it == "x" })
    }

    @Test
    fun corruptFileReadsAsEmpty() {
        File(folder.root, "water-storage.json").writeText("[{\"id\":\"a\",")
        assertEquals(emptyList<Item>(), store().list("water-storage"))
    }

    @Test
    fun writesNeverLeaveAHalfFile() {
        store().add("water-storage", mapOf("date" to JsonPrimitive(1L)))
        assertEquals(listOf("water-storage.json"), folder.root.list()!!.toList())
    }
}
