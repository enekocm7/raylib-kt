@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package raylib

/**
 * The version of the linked raylib library (e.g. `"6.0"`).
 *
 * The rest of the raylib API (window management, drawing, input, audio, etc.)
 * is exposed directly by the cinterop-generated bindings in this package.
 */
val RaylibVersion: String
    get() = RAYLIB_VERSION
