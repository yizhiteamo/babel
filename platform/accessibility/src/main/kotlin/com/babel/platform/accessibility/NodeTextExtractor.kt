package com.babel.platform.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import javax.inject.Inject

/**
 * Walks the accessibility tree and pulls out what is actually readable on
 * screen.
 *
 * Only `text` is collected, never `contentDescription`: the latter is a label
 * for screen readers, not something the user sees, so translating it would put
 * an overlay over an element that displays no text at all.
 */
class NodeTextExtractor @Inject constructor() {

    fun extract(root: AccessibilityNodeInfo): List<RawText> {
        val collected = mutableListOf<RawText>()
        collect(root, collected, depth = 0)
        return collected
    }

    /**
     * Collects only text that no descendant already accounts for, and returns
     * whether this subtree produced any.
     *
     * Containers carry text of their own while spanning their whole content: a
     * WebView's root reports the page title with the bounds of the entire view.
     * Rendering that would drop one overlay across the whole page, sized to it.
     * Taking the deepest text-bearing node instead keeps every overlay the size
     * of the text it actually replaces.
     */
    private fun collect(
        node: AccessibilityNodeInfo?,
        into: MutableList<RawText>,
        depth: Int,
    ): Boolean {
        if (node == null || depth > MAX_DEPTH) return false

        var descendantHadText = false
        for (index in 0 until node.childCount) {
            if (collect(node.getChild(index), into, depth + 1)) {
                descendantHadText = true
            }
        }
        if (descendantHadText) return true

        if (!node.isVisibleToUser) return false
        val text = node.text?.toString()
        if (text.isNullOrBlank()) return false

        val bounds = node.screenBounds()
        if (bounds.isEmpty) return false

        into += RawText(text = text, bounds = bounds, isPassword = node.isPassword)
        return true
    }

    private fun AccessibilityNodeInfo.screenBounds(): TextBounds {
        val rect = Rect()
        getBoundsInScreen(rect)
        return TextBounds(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            space = CoordinateSpace.SCREEN,
        )
    }

    private companion object {
        /** Guards against pathological or cyclic trees stalling a scan. */
        const val MAX_DEPTH = 60
    }
}
