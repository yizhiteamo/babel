package com.babel.domain.settings

/**
 * A chat service somebody might point Babel at, with its address filled in.
 *
 * ## Why this exists at all
 *
 * ADR 010 chose "an OpenAI-compatible endpoint, not a named service", and that
 * argument still holds for the *protocol*. What it did not anticipate is that a
 * user has to type the address, **path included** — a service's own
 * documentation usually gives a base URL, and `https://api.deepseek.com` alone
 * answers 404. That is not a reasonable thing to ask somebody to know, and it
 * was the first thing that went wrong when this was set up for real.
 *
 * So the vendor names are a convenience, not a constraint. [CUSTOM] is always
 * there, the address stays editable whichever preset is picked, and a server on
 * the user's own machine is one of the entries rather than an afterthought —
 * which is the case ADR 010's "no named service" rule existed to protect.
 *
 * ## Why the list is short
 *
 * Everything here is an address that can be wrong, and a wrong preset is worse
 * than no preset. These three are the ones this project has actually reached.
 * Adding another is a one-line change and should be made by somebody who has
 * just used it.
 *
 * ## Why the model stays a text field
 *
 * [defaultModel] is a starting point, not a menu. Model names change far faster
 * than an app ships, and a picker that cannot name this month's model is worse
 * than a box the user can type into. The preset fills it in; the user can
 * change it.
 */
data class ChatPreset(
    /** Stable across renames; what gets stored if this is ever persisted. */
    val id: String,
    val label: String,
    /** The full address, path included — which is the part users get wrong. */
    val endpoint: String,
    val defaultModel: String,
) {
    companion object {

        /**
         * No preset: type both fields.
         *
         * Not a vendor and deliberately first among equals — it is what keeps
         * "point it at something of your own" a first-class configuration.
         */
        val CUSTOM = ChatPreset(id = "custom", label = "Custom", endpoint = "", defaultModel = "")

        val DEEPSEEK = ChatPreset(
            id = "deepseek",
            label = "DeepSeek",
            endpoint = "https://api.deepseek.com/v1/chat/completions",
            defaultModel = "deepseek-chat",
        )

        val OPENAI = ChatPreset(
            id = "openai",
            label = "OpenAI",
            endpoint = "https://api.openai.com/v1/chat/completions",
            defaultModel = "gpt-4o-mini",
        )

        /**
         * A model on the user's own machine.
         *
         * Reached over loopback, which is the only cleartext the app permits
         * (`app/src/main/res/xml/network_security_config.xml`). From a phone
         * rather than the emulator this wants
         * `adb reverse tcp:11434 tcp:11434`, because a network security config
         * matches hosts and cannot say "anything on my wifi".
         */
        val OLLAMA = ChatPreset(
            id = "ollama",
            label = "Ollama (local)",
            endpoint = "http://localhost:11434/v1/chat/completions",
            defaultModel = "qwen2.5:7b",
        )

        /** In the order they are offered. */
        val ALL: List<ChatPreset> = listOf(DEEPSEEK, OPENAI, OLLAMA, CUSTOM)

        /**
         * The preset [settings] look like, or [CUSTOM] when none matches.
         *
         * Matched on the address alone: the model is expected to have been
         * changed, and changing it does not make the service a different one.
         */
        fun matching(settings: RemoteProviderSettings): ChatPreset =
            ALL.firstOrNull { it != CUSTOM && it.endpoint == settings.endpoint.trim() } ?: CUSTOM
    }
}
