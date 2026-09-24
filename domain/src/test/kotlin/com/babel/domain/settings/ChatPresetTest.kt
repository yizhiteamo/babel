package com.babel.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The presets, and the one property that makes them safe to have.
 *
 * A preset that fills in half an address is worse than no preset: the user
 * cannot tell what was filled in for them and what they have to finish. Every
 * entry therefore carries the **whole** address, path included — which is the
 * part a service's own documentation leaves out and the part that answers 404.
 */
class ChatPresetTest {

    @Test
    fun `every preset carries a full address and a model`() {
        for (preset in ChatPreset.ALL - ChatPreset.CUSTOM) {
            assertTrue(preset.endpoint.startsWith("http"), preset.label)
            // The path is the point. A base URL alone is what people paste and
            // what then 404s.
            assertTrue(preset.endpoint.contains("/chat/completions"), preset.label)
            assertTrue(preset.defaultModel.isNotBlank(), preset.label)
        }
    }

    @Test
    fun `custom carries nothing and is offered`() {
        assertEquals("", ChatPreset.CUSTOM.endpoint)
        assertEquals("", ChatPreset.CUSTOM.defaultModel)
        // Keeping "point it at something of your own" a first-class option is
        // what ADR 010's "not a named service" rule was protecting.
        assertTrue(ChatPreset.CUSTOM in ChatPreset.ALL)
    }

    @Test
    fun `a local server is one of the entries`() {
        // Reached over loopback, the only cleartext the app permits.
        assertTrue(ChatPreset.OLLAMA.endpoint.startsWith("http://localhost"))
    }

    @Test
    fun `settings are matched back to their preset by address`() {
        val settings = RemoteProviderSettings(
            endpoint = ChatPreset.DEEPSEEK.endpoint,
            // Changed from the default, which must not stop it matching: a
            // different model is the same service.
            model = "deepseek-reasoner",
        )
        assertEquals(ChatPreset.DEEPSEEK, ChatPreset.matching(settings))
    }

    @Test
    fun `an address nobody presets is custom`() {
        val settings = RemoteProviderSettings(
            endpoint = "https://example.invalid/v1/chat/completions",
            model = "some-model",
        )
        assertEquals(ChatPreset.CUSTOM, ChatPreset.matching(settings))
        assertEquals(ChatPreset.CUSTOM, ChatPreset.matching(RemoteProviderSettings()))
    }

    @Test
    fun `surrounding whitespace does not hide a preset`() {
        val settings = RemoteProviderSettings(endpoint = "  ${ChatPreset.OPENAI.endpoint}  ")
        assertEquals(ChatPreset.OPENAI, ChatPreset.matching(settings))
    }
}
