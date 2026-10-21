@file:OptIn(ExperimentalWasmJsInterop::class)

package raylib

import kotlin.js.ExperimentalWasmJsInterop

/**
 * The version of the raylib library this was built against (e.g. `"6.2"`).
 *
 * The rest of the raylib API is generated from raylib's `raylib_api.json` (see
 * `scripts/build-raylib-web.kt`) and calls the Emscripten build of raylib, which the page must load
 * into the global `raylib` before this module (see `resources/index.html`).
 */
val RaylibVersion: String
    get() = RAYLIB_VERSION

private const val RAYLIB_VERSION = "6.2"

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
 * Accepted for source compatibility and ignored: the browser paces the frames of [mainLoop] to the
 * display refresh rate, and raylib's frame-time wait would block the page.
 */
@Suppress("UNUSED_PARAMETER")
fun SetTargetFPS(fps: Int) = Unit

@JsFun("(frame) => { const tick = () => { frame(); requestAnimationFrame(tick); }; requestAnimationFrame(tick); }")
private external fun requestFrames(frame: () -> Unit)

actual fun mainLoop(frame: () -> Unit) = requestFrames(frame)

@JsFun(
    """(paths, done) => {
        const FS = raylib.FS;
        Promise.all(paths.split('\n').filter(Boolean).map(async (path) => {
            const response = await fetch(path);
            if (!response.ok) throw new Error('Could not load ' + path + ': ' + response.status);
            const data = new Uint8Array(await response.arrayBuffer());
            const slash = path.lastIndexOf('/');
            if (slash > 0) FS.createPath('/', path.substring(0, slash), true, true);
            FS.writeFile(path, data);
        })).then(() => done(), (error) => console.error(error));
    }"""
)
private external fun fetchIntoFileSystem(paths: String, done: () -> Unit)

/**
 * Downloads [paths] (relative to the page) into raylib's virtual file system, then calls [ready].
 *
 * raylib reads files through the virtual file system on the web, so a texture at `assets/ship.png`
 * is available to `LoadTexture("assets/ship.png")` once [ready] runs. Call this before the code that
 * loads assets, and start [mainLoop] from [ready].
 */
fun preloadAssets(vararg paths: String, ready: () -> Unit) = fetchIntoFileSystem(paths.joinToString("\n"), ready)
