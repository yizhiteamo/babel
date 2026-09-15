package com.babel.core.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ApiKeyTest {

    /**
     * The reason the type exists. Settings objects are held in UI state, passed
     * between layers and occasionally logged; a credential that cannot print
     * itself cannot leak that way.
     */
    @Test
    fun `printing a key never reveals it`() {
        val key = ApiKey("sk-not-a-real-key-000000")

        assertEquals("ApiKey(set)", key.toString())
        assertFalse(key.toString().contains("sk-"))
        assertFalse("$key".contains("000000"))
    }

    @Test
    fun `an unset key says so`() {
        assertEquals("ApiKey(unset)", ApiKey("").toString())
    }

    @Test
    fun `presence ignores whitespace`() {
        assertFalse(ApiKey("").isPresent)
        assertFalse(ApiKey("   ").isPresent)
        assertTrue(ApiKey("sk-x").isPresent)
    }

    /** The value is still reachable, deliberately, where the request is built. */
    @Test
    fun `the value can be read on purpose`() {
        assertEquals("sk-x", ApiKey("sk-x").value)
    }
}
