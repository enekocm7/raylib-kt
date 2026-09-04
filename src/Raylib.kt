@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package raylib

import kotlinx.cinterop.CValue
import kotlinx.cinterop.cValue

/**
 * The version of the linked raylib library (e.g. `"6.0"`).
 *
 * The rest of the raylib API (window management, drawing, input, audio, etc.)
 * is exposed directly by the cinterop-generated bindings in this package.
 */
val RaylibVersion: String
    get() = RAYLIB_VERSION

private fun color(red: Int, green: Int, blue: Int, alpha: Int = 255): CValue<Color> = cValue {
    r = red.toUByte()
    g = green.toUByte()
    b = blue.toUByte()
    a = alpha.toUByte()
}

val LIGHTGRAY: CValue<Color> get() = color(200, 200, 200)
val GRAY: CValue<Color> get() = color(130, 130, 130)
val DARKGRAY: CValue<Color> get() = color(80, 80, 80)
val YELLOW: CValue<Color> get() = color(253, 249, 0)
val GOLD: CValue<Color> get() = color(255, 203, 0)
val ORANGE: CValue<Color> get() = color(255, 161, 0)
val PINK: CValue<Color> get() = color(255, 109, 194)
val RED: CValue<Color> get() = color(230, 41, 55)
val MAROON: CValue<Color> get() = color(190, 33, 55)
val GREEN: CValue<Color> get() = color(0, 228, 48)
val LIME: CValue<Color> get() = color(0, 158, 47)
val DARKGREEN: CValue<Color> get() = color(0, 117, 44)
val SKYBLUE: CValue<Color> get() = color(102, 191, 255)
val BLUE: CValue<Color> get() = color(0, 121, 241)
val DARKBLUE: CValue<Color> get() = color(0, 82, 172)
val PURPLE: CValue<Color> get() = color(200, 122, 255)
val VIOLET: CValue<Color> get() = color(135, 60, 190)
val DARKPURPLE: CValue<Color> get() = color(112, 31, 126)
val BEIGE: CValue<Color> get() = color(211, 176, 131)
val BROWN: CValue<Color> get() = color(127, 106, 79)
val DARKBROWN: CValue<Color> get() = color(76, 63, 47)
val WHITE: CValue<Color> get() = color(255, 255, 255)
val BLACK: CValue<Color> get() = color(0, 0, 0)
val BLANK: CValue<Color> get() = color(0, 0, 0, 0)
val MAGENTA: CValue<Color> get() = color(255, 0, 255)
val RAYWHITE: CValue<Color> get() = color(245, 245, 245)


/**
 * Setup canvas (framebuffer) to start drawing, then calls [drawable].
 *
 * Drawing will end after [drawable] has finished.
 */

inline fun drawing(drawable: () -> Unit) {
    BeginDrawing()
    drawable()
    EndDrawing()
}


/**
 * Check if KEY_ESCAPE pressed or Close icon pressed
 */
inline val windowShouldClose: Boolean
    get() {
        return WindowShouldClose()
    }
