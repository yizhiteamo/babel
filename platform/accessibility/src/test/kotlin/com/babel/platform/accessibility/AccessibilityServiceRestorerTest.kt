package com.babel.platform.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the service may be put back, and what the list looks like when it is.
 */
class AccessibilityServiceRestorerTest {

    private fun should(
        wanted: Boolean = true,
        granted: Boolean = true,
        connected: Boolean = false,
        sinceLastWriteMs: Long = Long.MAX_VALUE,
    ) = AccessibilityServiceRestorer.shouldRestore(wanted, granted, connected, sinceLastWriteMs)

    @Test
    fun `a force stop with the permission is put back`() {
        assertTrue(should())
    }

    /** Switched off by the user, or by the vendor's security centre: both unbind. */
    @Test
    fun `a service that was unbound stays off`() {
        assertFalse(should(wanted = false))
    }

    @Test
    fun `without the permission nothing is written`() {
        assertFalse(should(granted = false))
    }

    @Test
    fun `a connected service is left alone`() {
        assertFalse(should(connected = true))
    }

    /** Binding takes seconds; writing again in the middle would restart it. */
    @Test
    fun `a bind in progress is not interrupted`() {
        assertFalse(should(sinceLastWriteMs = 5_000))
        assertTrue(should(sinceLastWriteMs = AccessibilityServiceRestorer.BIND_GRACE_MS))
    }

    private val ours = "com.yizhiteamo.babel/com.babel.platform.accessibility.BabelAccessibilityService"
    private val isOurs = { entry: String -> entry == ours || entry == "com.yizhiteamo.babel/.Short" }

    @Test
    fun `other services are kept as written`() {
        val lists = EnabledList.rewrite("other.app/.ItsService", ours, isOurs)

        assertEquals("other.app/.ItsService", lists.without)
        assertEquals("other.app/.ItsService:$ours", lists.with)
    }

    @Test
    fun `a list that still names it does not name it twice`() {
        val lists = EnabledList.rewrite("com.yizhiteamo.babel/.Short:other.app/.ItsService", ours, isOurs)

        assertEquals("other.app/.ItsService", lists.without)
        assertEquals("other.app/.ItsService:$ours", lists.with)
    }

    @Test
    fun `an empty list gains just this one`() {
        val lists = EnabledList.rewrite(null, ours, isOurs)

        assertEquals("", lists.without)
        assertEquals(ours, lists.with)
    }
}
