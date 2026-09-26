package com.babel.app.scope

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.babel.core.common.BabelLogger
import com.babel.domain.scope.DefaultTranslationScopePolicy
import com.babel.domain.scope.TranslationScopePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the default out-of-scope packages on this device
 * (`docs/systems/scope.md`).
 *
 * Everything but the system UI is looked up at runtime, so the policy follows
 * whichever launcher and keyboard the user actually installed instead of a
 * hardcoded list that would be wrong on most devices.
 *
 * The resolved set is cached: scope is checked on the acquisition path, which
 * must not hit `PackageManager`. [refresh] re-reads it, for when the user
 * switches launcher or keyboard.
 */
@Singleton
class AndroidTranslationScope @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: BabelLogger,
) : TranslationScopePolicy {

    /**
     * The rule itself lives in the domain; this class only answers the platform
     * half of the question — **which** packages are the launcher, the keyboard
     * and the system UI on this device.
     *
     * The two used to be separate copies of the same three lines, and the
     * domain's copy was the one with a test. Delegating makes that test cover
     * what actually runs.
     */
    @Volatile
    private var rule: TranslationScopePolicy = DefaultTranslationScopePolicy(emptySet())

    init {
        refresh()
    }

    override fun isInScope(packageName: String?): Boolean = rule.isInScope(packageName)

    fun refresh() {
        val resolved = buildSet {
            add(context.packageName)
            add(SYSTEM_UI_PACKAGE)
            addAll(launcherPackages())
            inputMethodPackage()?.let(::add)
        }
        rule = DefaultTranslationScopePolicy(resolved)
        logger.info(TAG, "out of scope: ${resolved.sorted()}")
    }

    /**
     * Every activity that handles HOME. Settings also registers as a fallback
     * home on some builds, and excluding it would wrongly silence a genuinely
     * text-heavy app, so it is kept in scope deliberately.
     */
    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .filterNot { it == SETTINGS_PACKAGE }
                .toSet()
        }.onFailure {
            logger.warn(TAG, "could not resolve launcher packages", it)
        }.getOrDefault(emptySet())
    }

    /** Stored as `package/class`; only the package part identifies the app. */
    private fun inputMethodPackage(): String? {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        return setting?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "TranslationScope"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
