package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractsTest {
    private val alarm = CapabilityDescriptor(
        id = "create_alarm",
        description = "Set an alarm on the phone",
        params = listOf(Param("hour", ParamType.INT), Param("minute", ParamType.INT)),
        permission = PermissionClass.PREPARE,
    )
    private val order = CapabilityDescriptor(
        id = "swiggy_place_order",
        description = "Place a prepared Swiggy Food order",
        params = listOf(Param("cart_id", ParamType.STRING)),
        permission = PermissionClass.COMMIT,
    )
    private val registry = listOf(alarm, order).associateBy { it.id }

    @Test
    fun acceptsValidCall() {
        val call = ToolCall("create_alarm", mapOf("hour" to 6, "minute" to 30))
        assertEquals(CallCheck.Ok(call, alarm), checkCall(call, registry))
    }

    @Test
    fun reportsMissingParams() {
        val call = ToolCall("create_alarm", mapOf("hour" to 6))
        assertEquals(CallCheck.Missing("create_alarm", listOf("minute")), checkCall(call, registry))
    }

    @Test
    fun rejectsUnknownToolUnknownArgAndWrongType() {
        assertTrue(checkCall(ToolCall("delete_everything", emptyMap()), registry) is CallCheck.Rejected)
        assertTrue(checkCall(ToolCall("create_alarm", mapOf("hour" to 6, "minute" to 30, "x" to 1)), registry) is CallCheck.Rejected)
        assertTrue(checkCall(ToolCall("create_alarm", mapOf("hour" to "six", "minute" to 30)), registry) is CallCheck.Rejected)
    }

    @Test
    fun paiseAcceptsOnlyWholeNumbers() {
        val pay = CapabilityDescriptor("pay", "Pay", listOf(Param("amount", ParamType.PAISE)), PermissionClass.COMMIT)
        val payRegistry = mapOf(pay.id to pay)
        assertTrue(checkCall(ToolCall("pay", mapOf("amount" to 23900)), payRegistry) is CallCheck.Ok)
        assertTrue(checkCall(ToolCall("pay", mapOf("amount" to 239.0)), payRegistry) is CallCheck.Rejected)
    }

    @Test
    fun onlyCommitNeedsConfirmation() {
        assertEquals(false, alarm.needsConfirmation)
        assertEquals(true, order.needsConfirmation)
    }
}
