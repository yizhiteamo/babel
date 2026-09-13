package com.babel.core.testing

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.core.model.TranslationRequest

/** Fixtures that keep tests focused on the field under test. */
object TestElements {

    const val DEFAULT_PACKAGE: String = "com.example.reader"

    fun bounds(
        left: Int = 0,
        top: Int = 0,
        right: Int = 200,
        bottom: Int = 48,
        space: CoordinateSpace = CoordinateSpace.SCREEN,
    ): TextBounds = TextBounds(left, top, right, bottom, space)

    fun element(
        id: String = "e1",
        text: String = "Hello",
        revision: Long = 0,
        bounds: TextBounds = bounds(),
        isProtected: Boolean = false,
        packageName: String? = DEFAULT_PACKAGE,
        windowId: Int? = 1,
        metadata: Map<String, String> = emptyMap(),
        sourceType: TextSourceType = TextSourceType.ACCESSIBILITY,
    ): TextElement = TextElement(
        id = TextElementId(id),
        text = text,
        bounds = bounds,
        sourceType = sourceType,
        source = SourceIdentity(packageName = packageName, windowId = windowId),
        revision = Revision(revision),
        isProtected = isProtected,
        metadata = metadata,
    )

    fun request(
        elementId: String = "e1",
        text: String = "Hello",
        revision: Long = 0,
        source: String? = null,
        target: String = "zh-Hans",
        requestId: String = "r1",
    ): TranslationRequest = TranslationRequest(
        requestId = RequestId(requestId),
        elementId = TextElementId(elementId),
        revision = Revision(revision),
        sourceText = text,
        languages = LanguagePair(
            source = source?.let(::LanguageTag),
            target = LanguageTag(target),
        ),
    )
}
