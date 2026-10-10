package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ZeptoAuthTest {
    @Test
    fun verifierIsUrlSafeAndUnique() {
        val first = pkceVerifier()
        val second = pkceVerifier()
        assertNotEquals(first, second)
        assertTrue(first.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun challengeIsSha256OfVerifier() {
        val verifier = "fixed-test-verifier-value-for-hashing"
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest)
        assertEquals(expected, pkceChallenge(verifier))
    }

    @Test
    fun formEncodeEscapesReservedCharacters() {
        val encoded = formEncode(linkedMapOf("redirect_uri" to "com.nila.launcher://zepto-oauth", "a b" to "c&d"))
        assertEquals("redirect_uri=com.nila.launcher%3A%2F%2Fzepto-oauth&a+b=c%26d", encoded)
    }
}
