package com.nila.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicMcpToolRegistryTest {
    @Test
    fun parsesFlatScalarSchemaAsConfirmedCapability() {
        val tools = JSONArray().put(
            JSONObject()
                .put("name", "search_places")
                .put("description", "Find nearby places")
                .put("inputSchema", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string"))
                        .put("limit", JSONObject().put("type", "integer")))
                    .put("required", JSONArray().put("query"))),
        )

        val tool = DynamicMcpToolRegistry.parse("maps", "Maps MCP", tools).single()

        assertEquals("mcp_maps_search_places", tool.capability.id)
        assertEquals(PermissionClass.COMMIT, tool.capability.permission)
        assertEquals(listOf(Param("query", ParamType.STRING), Param("limit", ParamType.INT, required = false)), tool.capability.params)
    }

    @Test
    fun skipsToolsWithNestedOrUnknownInputTypes() {
        val nested = JSONObject()
            .put("name", "nested")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("payload", JSONObject().put("type", "object"))))
        val unknown = JSONObject()
            .put("name", "unknown")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("payload", JSONObject().put("type", "array"))))

        assertTrue(DynamicMcpToolRegistry.parse("demo", "Demo", JSONArray().put(nested).put(unknown)).isEmpty())
    }

    @Test
    fun rejectsMalformedNamesAndRequiredFieldsWithoutProperties() {
        val malformed = JSONObject()
            .put("name", "bad tool")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
        val missing = JSONObject()
            .put("name", "valid")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())
                .put("required", JSONArray().put("not_declared")))

        assertTrue(DynamicMcpToolRegistry.parse("demo", "Demo", JSONArray().put(malformed).put(missing)).isEmpty())
    }

    @Test
    fun dynamicCallsWaitForConfirmationAndRunOnlyOnce() {
        val tool = DynamicMcpToolRegistry.parse("demo", "Demo", JSONArray().put(
            JSONObject().put("name", "write_item").put("inputSchema", JSONObject()
                .put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("value"))),
        )).single()
        val engine = NilaEngine()
        engine.replaceMcpTools("demo", listOf(tool))
        val pending = engine.execute(NilaIntent.Call(ToolCall(tool.capability.id, mapOf("value" to "safe")))) as NilaResponse.NeedsConfirmation

        assertTrue(engine.confirm(pending.token) is NilaResponse.McpToolInvocation)
        assertTrue(engine.confirm(pending.token) is NilaResponse.Text)
    }
}
