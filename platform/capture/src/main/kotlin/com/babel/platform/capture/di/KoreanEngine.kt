package com.babel.platform.capture.di

import javax.inject.Qualifier

/**
 * The Korean reader, as against the default one.
 *
 * Two instances of the same class with different models, so they have to be
 * told apart by something other than their type.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class KoreanEngine
