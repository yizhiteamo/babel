package com.babel.domain.settings

import com.babel.core.model.ApiKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two services, two credentials, and which one is in force.
 *
 * One shared key used to serve both, so configuring the second service
 * destroyed the first one's credential and switching back sent a DeepL key to a
 * chat endpoint. These guard that the fields stay apart.
 */
class RemoteProviderSettingsTest {

    @Test
    fun `each service reads its own key`() {
        val settings = RemoteProviderSettings(
            chatKey = ApiKey("chat-secret"),
            deepLKey = ApiKey("deepl-secret:fx"),
        )

        assertEquals("chat-secret", settings.copy(service = RemoteService.CHAT).apiKey.value)
        assertEquals("deepl-secret:fx", settings.copy(service = RemoteService.DEEPL).apiKey.value)
    }

    @Test
    fun `storing one key leaves the other alone`() {
        val settings = RemoteProviderSettings(chatKey = ApiKey("chat-secret"))
            .withKeyFor(RemoteService.DEEPL, ApiKey("deepl-secret:fx"))

        // The defect this replaced: the chat key was gone at this point.
        assertEquals("chat-secret", settings.chatKey.value)
        assertEquals("deepl-secret:fx", settings.deepLKey.value)
    }

    @Test
    fun `switching service does not move a credential`() {
        val settings = RemoteProviderSettings(
            service = RemoteService.DEEPL,
            deepLKey = ApiKey("deepl-secret:fx"),
        )

        // Switching to a chat route with no chat key must not hand it DeepL's,
        // which is what produced a 401 that looked like a bug in the endpoint.
        assertEquals("", settings.copy(service = RemoteService.CHAT).apiKey.value)
    }

    @Test
    fun `a chat route needs an address and a model, not a key`() {
        val local = RemoteProviderSettings(
            endpoint = "http://localhost:11434/v1/chat/completions",
            model = "qwen2.5:7b",
        )
        // A model on the user's own machine wants no credential, and that is
        // the one configuration where text never reaches the internet.
        assertTrue(local.isConfigured)
        assertFalse(local.copy(model = "").isConfigured)
        assertFalse(local.copy(endpoint = "").isConfigured)
    }

    @Test
    fun `DeepL needs its own key and nothing else`() {
        val deepL = RemoteProviderSettings(service = RemoteService.DEEPL)
        assertFalse(deepL.isConfigured)
        // A chat key left over from the other service must not count.
        assertFalse(deepL.copy(chatKey = ApiKey("chat-secret")).isConfigured)
        assertTrue(deepL.copy(deepLKey = ApiKey("abc:fx")).isConfigured)
    }
}
