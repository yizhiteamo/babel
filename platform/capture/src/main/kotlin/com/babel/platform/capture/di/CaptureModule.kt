package com.babel.platform.capture.di

import com.babel.domain.vision.ImageTextScanner
import com.babel.domain.vision.RecognizerModel
import com.babel.platform.capture.CaptureTextSource
import com.babel.platform.capture.DetectingPageReader
import com.babel.platform.capture.BalloonEngine
import com.babel.platform.capture.MangaOcrRecognizer
import com.babel.platform.capture.MlKitTextRecognizer
import com.babel.platform.capture.ModelDownloader
import com.babel.platform.capture.OnnxBubbleDetector
import com.babel.platform.capture.PageReader
import com.babel.platform.capture.TextDetector
import com.babel.platform.capture.TextRecognizer
import dagger.Binds
import com.babel.core.common.BabelLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Publishes scanning as the domain contract, so the accessibility service can
 * drive it without depending on this module — which would pull ML Kit into the
 * V1 path (ADR 009).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureModule {

    @Binds
    @Singleton
    abstract fun bindImageTextScanner(impl: CaptureTextSource): ImageTextScanner
}

/**
 * Kept separate and internal because both sides of this binding are internal:
 * nothing outside needs to know which engine reads the pixels, which is the
 * point of the abstraction.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecognizerModule {


    /**
     * The balloon engine as a capability rather than as manga-ocr.
     *
     * `BubbleRecognizer` decides between two engines and has to be testable on
     * that decision, which means neither of them may be a concrete class it
     * cannot stand in for.
     */
    @Binds
    @Singleton
    abstract fun bindBalloonEngine(impl: MangaOcrRecognizer): BalloonEngine

    /**
     * The one binding here whose *type* is public: the interface has to show
     * whether the recogniser's weights are on the device and offer to fetch
     * them (ADR 011). Declared in this module because the implementation is
     * internal like everything else that touches an engine — what leaves is a
     * domain contract with no Android type in it.
     */
    @Binds
    @Singleton
    abstract fun bindRecognizerModel(impl: ModelDownloader): RecognizerModel

    @Binds
    @Singleton
    abstract fun bindTextDetector(impl: OnnxBubbleDetector): TextDetector

    /**
     * The detecting reader, which falls back to the grouping one by itself when
     * no model is present. Bound this way round so that the fallback is a
     * dependency of the thing that needs it rather than a decision taken here —
     * the condition is "is there a model", and only the detector knows.
     */
    @Binds
    @Singleton
    abstract fun bindPageReader(impl: DetectingPageReader): PageReader

    companion object {
        /**
         * The general reader: ML Kit's Japanese model, which reads Latin too.
         * Provided rather than bound because both readers are the same class
         * and differ only in the model they are given.
         */
        @Provides
        @Singleton
        fun provideTextRecognizer(logger: BabelLogger): TextRecognizer =
            MlKitTextRecognizer(logger, MlKitTextRecognizer.Script.JAPANESE)

        /**
         * The Korean reader, consulted once a page to answer a question no
         * Japanese engine can: manga-ocr invents fluent Japanese for Korean
         * artwork, so only Hangul found by a Korean model proves the page is
         * Korean (`KoreanScript`).
         */
        @Provides
        @Singleton
        @KoreanEngine
        fun provideKoreanRecognizer(logger: BabelLogger): TextRecognizer =
            MlKitTextRecognizer(logger, MlKitTextRecognizer.Script.KOREAN)
    }
}
