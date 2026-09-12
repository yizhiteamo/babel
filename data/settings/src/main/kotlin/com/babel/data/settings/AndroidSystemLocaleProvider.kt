package com.babel.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.babel.core.model.LanguageTag
import com.babel.domain.language.SystemLocaleProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports the **device** language, which is what translation follows.
 *
 * The distinction matters because Babel's own UI is localised too: a user may
 * run the interface in English while the device stays in Chinese, and that must
 * not change what text gets translated into (ADR 003,
 * `docs/systems/language.md`).
 *
 * Neither `Locale.getDefault()` nor `Resources.getSystem()` is safe here. Both
 * reflect the per-app locale on Android 13+, so reading either would make
 * choosing an English interface silently switch the translation target to
 * English — verified on device, where setting a per-app locale moved the target
 * from `zh` to `en`. [LocaleManager.getSystemLocales] is the API that answers
 * the question actually being asked; the configuration read is only a fallback
 * for older versions, where no per-app locale exists to confuse it.
 */
@Singleton
class AndroidSystemLocaleProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SystemLocaleProvider {

    override fun current(): LanguageTag {
        val locale = systemLocale() ?: Locale.getDefault()
        return LanguageTag(locale.toLanguageTag())
    }

    private fun systemLocale(): Locale? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val systemLocales = context.getSystemService(LocaleManager::class.java)?.systemLocales
            if (systemLocales != null && !systemLocales.isEmpty) return systemLocales[0]
        }

        val configured = Resources.getSystem().configuration.locales
        return if (configured.isEmpty) null else configured[0]
    }
}
