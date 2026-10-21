package raylib

actual fun mainLoop(frame: () -> Unit) {
    while (!windowShouldClose) frame()
}
