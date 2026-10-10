package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeptoMcpClientTest {
    @Test
    fun noHeadersBeforeSessionOrVersionAreKnown() {
        assertEquals(emptyMap<String, String>(), rpcHeaders(sessionId = null, protocolVersion = null))
    }

    @Test
    fun carriesTheNegotiatedProtocolVersionAfterInitialize() {
        val headers = rpcHeaders(sessionId = "session-123", protocolVersion = "2025-06-18")
        assertEquals("session-123", headers["Mcp-Session-Id"])
        // Streamable HTTP requires this on every request after initialize, or servers default to 2025-03-26.
        assertEquals("2025-06-18", headers["MCP-Protocol-Version"])
    }
}
