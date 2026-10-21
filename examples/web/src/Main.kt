package example

import raylib.*

fun main() {
    InitWindow(600, 400, "raylib-kt")
    SetTargetFPS(60)

    // raylib reads files from a virtual file system on the web: download them into it first.
    preloadAssets("assets/gradient.png") {
        val texture = LoadTexture("assets/gradient.png")

        // On the web the frames are scheduled by the browser, so mainLoop returns immediately.
        mainLoop {
            val mouse = GetMousePosition()
            drawing {
                ClearBackground(RAYWHITE)
                DrawText("Hello from kotlin", 200, 200, 20, BLACK)
                DrawTexture(texture, 268, 240, WHITE)
                DrawCircleV(mouse, 12f, if (IsMouseButtonDown(MOUSE_BUTTON_LEFT)) RED else BLUE)
                DrawFPS(10, 10)
            }
        }
    }
}
