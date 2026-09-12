package com.babel.platform.overlay

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import javax.inject.Inject

/**
 * The single place screen coordinates become overlay coordinates.
 *
 * `AccessibilityNodeInfo.getBoundsInScreen` is absolute to the display, while a
 * child view is positioned relative to the overlay container. Those differ by
 * wherever the system placed the container — status bar, cutout, gesture
 * insets. `docs/systems/overlay.md` requires this conversion to live in one
 * place rather than being corrected ad hoc at each render site.
 */
class CoordinateMapper @Inject constructor() {

    /** Offset of the overlay container on screen, from `getLocationOnScreen`. */
    var containerOriginX: Int = 0
        private set

    var containerOriginY: Int = 0
        private set

    fun updateContainerOrigin(x: Int, y: Int) {
        containerOriginX = x
        containerOriginY = y
    }

    fun toRenderSpace(source: TextBounds): TextBounds {
        require(source.space == CoordinateSpace.SCREEN) {
            "expected SCREEN bounds, got ${source.space}"
        }

        return TextBounds(
            left = source.left - containerOriginX,
            top = source.top - containerOriginY,
            right = source.right - containerOriginX,
            bottom = source.bottom - containerOriginY,
            space = CoordinateSpace.RENDER,
        )
    }
}
