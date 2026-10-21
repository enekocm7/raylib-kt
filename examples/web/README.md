# raylib-kt web example

A minimal raylib-kt app that runs in the browser: Kotlin/Wasm for your code, raylib compiled with Emscripten for the rendering. It draws text, a texture loaded from a file, and a circle that follows the mouse.

## Run the example

From the repository root, build raylib for the web (the first run downloads emsdk, which is a few hundred MB):

```shell
kargo script scripts/build-raylib-web.kt
```

This writes `raylib.mjs` and `raylib.wasm` to `build/web/dist/` and copies them into `examples/web/resources/`. Then:

```shell
cd examples/web
kargo run
```

Open <http://127.0.0.1:8080/>. Use `kargo run --port 0` to pick a free port. Kargo serves the app as it is and does not reload the page, so run it again after you change the code.

## How the pieces fit

```
your Kotlin code ──(raylib-kt, Kotlin/Wasm)──▶ JS glue ──▶ raylib.wasm (Emscripten, WebGL)
                                                    ▲
                                              raylib.mjs
```

Two WebAssembly modules run side by side: the one Kotlin compiles (your code and the raylib-kt bindings) and `raylib.wasm`. The bindings call the Emscripten module through a global named `raylib`, so the page must create that global before the Kotlin module starts. That is what `resources/index.html` does.

## Set up your own project

### 1. Create the project

```shell
kargo new my-game -t wasm
cd my-game
```

In `kargo.toml`, use the browser environment and add raylib-kt:

```toml
[target.wasm]
environment = "browser"

[dependencies]
"io.github.enekocm7:raylib-kt" = "0.4.0"
```

To build against a checkout of this repository instead, use `raylib-kt = { path = "../raylib-kt" }`, as this example does.

### 2. Get `raylib.mjs` and `raylib.wasm`

The Kotlin library contains only the bindings, not raylib itself. Build the two files from a checkout of this repository (see above) and copy them next to your page:

```shell
cp raylib-kt/build/web/dist/raylib.mjs raylib-kt/build/web/dist/raylib.wasm my-game/resources/
```

### 3. Add the HTML page

Copy [`resources/index.html`](resources/index.html) to `my-game/resources/index.html`. Kargo copies everything below `resources/` next to the compiled module and uses your `index.html` instead of the one it would generate.

The page does three things, in this order:

1. imports `raylib.mjs` and starts the Emscripten module with the `<canvas>`,
2. stores the result in `globalThis.raylib`,
3. imports your Kotlin module, which runs `main` as soon as it loads.

Change the last import to your module name, which is the package name unless you set `module-name`. For `name = "my-game"` it is `./my-game.mjs`. If the Kotlin module is imported before `globalThis.raylib` exists, the first raylib call fails with `raylib is not defined`.

Keep the `<canvas>` free of CSS sizing. raylib reads the canvas size itself, and scaling it with CSS offsets the mouse position.

### 4. Write the app

```kotlin
package mygame

import raylib.*

fun main() {
    InitWindow(800, 450, "my game")

    mainLoop {
        drawing {
            ClearBackground(RAYWHITE)
            DrawText("Hello from Kotlin/Wasm", 190, 200, 20, BLACK)
        }
    }
}
```

Use `mainLoop { ... }` instead of `while (!windowShouldClose)`. A browser tab cannot run a blocking loop, so on the web `mainLoop` schedules frames with `requestAnimationFrame` and returns immediately. Code after it does not run when the window closes. The same source compiles for native targets, where `mainLoop` is the usual loop.

### 5. Load assets

raylib reads files from Emscripten's virtual file system, not from the network. Put the files under `resources/` (for example `resources/assets/ship.png`) and download them into the virtual file system before loading them:

```kotlin
preloadAssets("assets/ship.png") {
    val ship = LoadTexture("assets/ship.png")
    mainLoop {
        drawing { DrawTexture(ship, 100, 100, WHITE) }
    }
}
```

Paths are relative to the page and are the same paths you pass to raylib. Start `mainLoop` from the callback, so no frame runs before the files exist.

### 6. Run it

```shell
kargo run
```

For a smaller, faster build, use `kargo run --release`. If `wasm-opt` from Binaryen is on your `PATH`, Kargo runs it on release builds.

## Differences from native

- Structs are plain Kotlin data classes, for example `Color(r, g, b, a = 255)` with `Int` components, not `CValue<Color>`.
- Functions that take or return pointers, arrays, callbacks or varargs are not bound, and `raymath` and `rlgl` are not exposed.
- `SetTargetFPS` is accepted and ignored. The browser paces frames to the display refresh rate.
- `windowShouldClose` and `WaitTime` are not available.
- Audio starts only after a user gesture, as browsers require.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `raylib is not defined` in the console | The Kotlin module loaded before `globalThis.raylib` was set. Check the order in `index.html`. |
| 404 for `raylib.wasm` or `raylib.mjs` | The two files are not in `resources/`. Copy them from `build/web/dist/`. |
| Blank canvas, `LoadTexture` fails | The asset was not passed to `preloadAssets`, or the path differs from the one given to raylib. |
| Mouse position is offset | The canvas is scaled with CSS. |
| The page does not update after a code change | `kargo run` does not reload. Run it again and refresh the page. |
| `kargo build` fails on `macos_arm64` | Kotlin/Native cannot generate Apple bindings off macOS. Remove `"macos_arm64"` from `platforms` in the library's `kargo.toml` when you build it on another OS. |
