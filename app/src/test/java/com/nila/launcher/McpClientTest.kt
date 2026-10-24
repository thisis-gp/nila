package com.nila.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpClientTest {
    @Test
    fun collectsPaginatedToolListsInOrder() {
        val cursors = mutableListOf<String?>()
        val tools = collectMcpPages("tools") { cursor ->
            cursors += cursor
            if (cursor == null) JSONObject().put("tools", JSONArray().put("first")).put("nextCursor", "page-2")
            else JSONObject().put("tools", JSONArray().put("second"))
        }
        assertEquals(listOf(null, "page-2"), cursors)
        assertEquals("first", tools.getString(0))
        assertEquals("second", tools.getString(1))
    }

    @Test
    fun rejectsRepeatedPaginationCursor() {
        assertThrows(IllegalArgumentException::class.java) {
            collectMcpPages("prompts") { JSONObject().put("prompts", JSONArray()).put("nextCursor", "same") }
        }
    }

    @Test
    fun appliesPageBoundsToEveryListKind() {
        val tooMany = JSONArray().apply { repeat(257) { put(it) } }
        assertThrows(IllegalArgumentException::class.java) {
            collectMcpPages("resources") { JSONObject().put("resources", tooMany) }
        }
    }
}
