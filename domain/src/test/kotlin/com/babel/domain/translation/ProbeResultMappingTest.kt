package com.babel.domain.translation

import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the user is told, whichever way the trouble is found.
 *
 * The same mapping serves the Test button in the settings dialog and the line
 * on the home card during use, so one cause cannot get two explanations.
 *
 * The value of it is that each case names the field to fix. "It failed" sends
 * somebody back to four boxes with no idea which one is wrong, and the HTTP
 * status is the only thing separating a stale key from a mistyped address from
 * a model that does not exist on that account.
 */
class ProbeResultMappingTest {

    private fun rejected(status: Int?) = TranslationError.ProviderRejected(
        provider = ProviderId("test"),
        reason = "HTTP $status",
        status = status,
    )

    @Test
    fun `a rejected credential is named as one`() {
        assertEquals(ProbeResult.KeyRejected, probeResultOf(rejected(401)))
        assertEquals(ProbeResult.KeyRejected, probeResultOf(rejected(403)))
    }

    @Test
    fun `a missing path is the address, not the key`() {
        // The mistake the preset list exists to prevent: pasting a base URL
        // without `/v1/chat/completions`.
        assertEquals(ProbeResult.EndpointNotFound, probeResultOf(rejected(404)))
    }

    @Test
    fun `a refused request points at the model name`() {
        assertEquals(ProbeResult.RequestRejected, probeResultOf(rejected(400)))
        assertEquals(ProbeResult.RequestRejected, probeResultOf(rejected(422)))
    }

    @Test
    fun `a spent quota is not a broken key`() {
        // DeepL's own code, and the distinction matters: the key is fine, and
        // telling somebody to check it sends them to the wrong place.
        assertEquals(ProbeResult.QuotaExhausted, probeResultOf(rejected(456)))
        assertEquals(ProbeResult.QuotaExhausted, probeResultOf(TranslationError.RateLimited))
    }

    @Test
    fun `nothing answering is unreachable`() {
        assertEquals(
            ProbeResult.Unreachable("SocketTimeoutException"),
            probeResultOf(TranslationError.Network("SocketTimeoutException")),
        )
        assertEquals(ProbeResult.Unreachable(), probeResultOf(TranslationError.Offline))
    }

    @Test
    fun `the on-device engine declining a language is its own message`() {
        // ML Kit has no model for every language, and that was silent too. It
        // is the default route, so it is the one most people would meet.
        assertEquals(
            ProbeResult.LanguageUnsupported,
            probeResultOf(TranslationError.Unsupported("no on-device model for target language")),
        )
    }

    @Test
    fun `a status with no name for it is reported as itself`() {
        assertEquals(ProbeResult.Unexpected(418), probeResultOf(rejected(418)))
    }

    @Test
    fun `a rejection that never reached HTTP says so`() {
        // `isConfigured` failing, or a pair the service declines outright —
        // not something a status could explain.
        assertEquals(ProbeResult.NotConfigured, probeResultOf(rejected(null)))
    }
}
