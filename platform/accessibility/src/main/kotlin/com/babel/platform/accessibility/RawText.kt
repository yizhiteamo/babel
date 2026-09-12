package com.babel.platform.accessibility

import com.babel.core.model.TextBounds

/**
 * One piece of text read off the accessibility tree, before it becomes a
 * [com.babel.core.model.TextElement].
 *
 * This exists so identity assignment stays testable: `AccessibilityNodeInfo`
 * cannot be constructed in a JVM unit test, but the rules that turn a screen
 * full of text into stable ids can be — and those rules decide whether
 * scrolling re-translates.
 */
data class RawText(
    val text: String,
    val bounds: TextBounds,
    val isPassword: Boolean = false,
)
