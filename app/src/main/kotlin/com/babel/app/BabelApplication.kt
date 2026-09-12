package com.babel.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Composition root. Modules are wired here so no other module needs a global
 * singleton or service locator.
 */
@HiltAndroidApp
class BabelApplication : Application()
