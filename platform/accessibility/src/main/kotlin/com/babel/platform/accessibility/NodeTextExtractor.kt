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

    private fun collect(node: AccessibilityNodeInfo?, into: MutableList<RawText>, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return

        if (node.isVisibleToUser) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                val bounds = node.screenBounds()
                if (!bounds.isEmpty) {
                    into += RawText(
                        text = text,
                        bounds = bounds,
                        isPassword = node.isPassword,
                    )
                }
            }
        }

        // Children are visited even when this node had text: a container can
        // carry a summary while its children hold the real content.
        for (index in 0 until node.childCount) {
            collect(node.getChild(index), into, depth + 1)
        }
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
