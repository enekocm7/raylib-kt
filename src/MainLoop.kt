package raylib

/**
 * Runs [frame] once per rendered frame until the window should close.
 *
 * This is the portable replacement for `while (!windowShouldClose) { ... }`:
 *
 * - On native targets it is that loop, and returns once the window is closed.
 * - On the web a browser tab cannot run a blocking loop, so the frames are scheduled with
 *   `requestAnimationFrame` and this function returns immediately. Code after the call runs
 *   before the first frame, so put per-frame logic inside [frame] and don't rely on code after it.
 */
expect fun mainLoop(frame: () -> Unit)
