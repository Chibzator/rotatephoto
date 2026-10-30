package com.rotatefix.app

/**
 * What the user selects on the bubble. The bubble shows an arrow that should
 * point to the sky while the phone is held for the shot.
 *
 * [extra] is the clockwise rotation (degrees) added on top of whatever the
 * camera app wrote, which (with dead sensors) is always "phone upright".
 * [arrowAngle] is how the arrow is drawn on the (non-rotating) screen.
 */
enum class Mode(val extra: Int, val arrowAngle: Float, val label: String) {
    UP(0, 0f, "Portrait"),
    RIGHT(270, 90f, "Landscape · top of phone to the left"),
    LEFT(90, -90f, "Landscape · top of phone to the right"),
    OFF(-1, 0f, "Off · photos left untouched");
}
