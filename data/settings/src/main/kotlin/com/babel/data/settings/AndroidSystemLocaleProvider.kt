package com.babel.data.settings

import android.content.res.Resources
import com.babel.core.model.LanguageTag
import com.babel.domain.language.SystemLocaleProvider
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports the **device** language.
 *
 * Deliberately not `Locale.getDefault()`: that reflects the per-app locale, so
 * a user who runs Babel's own UI in English would silently start translating
 * everything into English too. App UI language and translation target language
 * are separate concepts (ADR 003), and this is where that separation is real.
 */
@Singleton
class AndroidSystemLocaleProvider @Inject constructor() : SystemLocaleProvider {

    override fun current(): LanguageTag {
        val locales = Resources.getSystem().configuration.locales
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
        return LanguageTag(locale.toLanguageTag())
    }
}
