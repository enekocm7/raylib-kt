# raylib-kt

Kotlin/Native bindings for [raylib 6.2](https://www.raylib.com/).

Supported platforms:

- Linux x86-64 (`linuxX64`)
- Windows x86-64 (`mingwX64`)
- macOS Apple Silicon (`macosArm64`)
- Web (Kotlin/Wasm in the browser, see [Web](#web))

## Install

### Gradle Kotlin Multiplatform

Add the dependency to your native source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.enekocm7:raylib-kt:0.4.0")
        }
    }
}
```

### Kargo

Add the dependency to your `kargo.toml`:

```toml
[dependencies]
"io.github.enekocm7:raylib-kt" = "0.4.0"
```

The API is available from the `raylib` package:

```kotlin
import raylib.*
```

## Example

```kotlin
fun main() {
    InitWindow(600, 400, "raylib-kt")

    SetTargetFPS(60)
    while (!windowShouldClose) {
        drawing {
            ClearBackground(RAYWHITE)
            DrawText("Hello from kotlin", 200, 200, 20, BLACK)
        }
    }

    CloseWindow()
}
```

## Web

raylib-kt can run in the browser, like C raylib's `PLATFORM_WEB`. Kotlin/Native has no Emscripten target, so the web target is Kotlin/Wasm (`[target.wasm] environment = "browser"`) talking to raylib compiled with Emscripten (`PLATFORM_WEB`, OpenGL ES 2.0 / WebGL).

Build the library for the web with:

```shell
python scripts/build-raylib-web.py
```

This downloads and activates a pinned [emsdk](https://github.com/emscripten-core/emsdk) under `build/` (it needs `git` and Python 3), generates `src-wasm/RaylibBindings.kt` and a C shim from raylib's `raylib_api.json`, and writes `raylib.mjs` and `raylib.wasm` to `build/web/dist/`. The task is also available as `kargo task raylib-web`.

### Using it

[`examples/web`](examples/web) is a complete project. Its `resources/index.html` is the HTML shell to copy: it loads `raylib.mjs`, exposes it as the global `raylib`, and only then imports the Kotlin module (which runs `main` as soon as it loads). Put `raylib.mjs` and `raylib.wasm` next to it (the build script copies them into `examples/web/resources/`) and run `kargo run` there.

- **Main loop.** A browser tab cannot run a blocking `while (!windowShouldClose)` loop. Use `mainLoop { ... }` instead: on native it is that loop, on the web it schedules frames with `requestAnimationFrame` and returns immediately, so code after it does not run when the window closes.
- **Frame rate.** `SetTargetFPS` is accepted and ignored on the web; the browser paces frames to the display. `windowShouldClose` and `WaitTime` are not available.
- **Assets.** raylib reads files from Emscripten's virtual file system. Call `preloadAssets("assets/ship.png", "assets/hit.wav") { ... }` to download files (paths relative to the page) into it, then load them as usual and start `mainLoop` from the callback.
- **Shipping `raylib.mjs`/`raylib.wasm`.** The Kotlin/Wasm library only contains the bindings; it does not bundle the Emscripten build. Copy the two files into your page next to the generated module, as in the example.

### Differences from the native API

The web bindings are generated, so function and constant names match raylib, but the types differ because there is no cinterop:

- Structs are plain Kotlin data classes (`Color`, `Vector2`, `Rectangle`, `Texture`, ...). `Color` is `Color(r, g, b, a = 255)` with `Int` components, not a `CValue<Color>`.
- Only functions whose arguments are scalars, strings and structs are bound (about 440 of raylib's 620). Anything taking or returning a pointer, array, callback or varargs (for example `DrawTriangleFan`, `LoadFileData`, `SetTraceLogCallback`, `TextFormat`, `SetShaderValue`) is not available yet.
- `raymath.h` and `rlgl.h` are not exposed.
- Native-only raylib features do not apply on the web: threads, file dialogs, and most window management (monitors, positions, minimizing). Audio starts only after a user gesture, as browsers require.

## Requirements

The published package includes the raylib static libraries, so you do not need to install raylib separately.

Building from source needs [Kargo](https://enekocm7.github.io/kargo/), which downloads the JDK, Kotlin/Native compiler and native toolchains automatically.

### Debian and Ubuntu

```shell
sudo apt update
sudo apt install git curl tar unzip \
  libasound2-dev libgl1-mesa-dev libglu1-mesa-dev \
  libwayland-dev libwayland-bin libxkbcommon-dev \
  libx11-dev libxrandr-dev libxi-dev libxcursor-dev libxinerama-dev
```

### Fedora

```shell
sudo dnf install git curl tar unzip \
  alsa-lib-devel mesa-libGL-devel mesa-libGLU-devel \
  wayland-devel libxkbcommon-devel \
  libX11-devel libXrandr-devel libXi-devel libXcursor-devel libXinerama-devel
```

### Arch Linux

```shell
sudo pacman -S --needed base-devel git curl tar unzip \
  alsa-lib mesa glu wayland libxkbcommon \
  libx11 libxrandr libxi libxcursor libxinerama
```

### openSUSE

```shell
sudo zypper install git-core curl tar unzip \
  alsa-devel Mesa-libGL-devel Mesa-libGLU-devel \
  wayland-devel libxkbcommon-devel \
  libX11-devel libXrandr-devel libXi-devel libXcursor-devel libXinerama-devel
```

For other Linux distributions, install the equivalent X11, Wayland, xkbcommon, OpenGL, and ALSA development packages. The `wayland-scanner` command must be available on `PATH`.

### macOS

Install the Xcode command-line tools so `xcrun` and the macOS SDK are available:

```shell
xcode-select --install
```

## Build from source

Clone the repository:

```shell
git clone https://github.com/enekocm7/raylib-kt.git
cd raylib-kt
```

```shell
kargo build
```

`kargo build` first runs the `raylib` task (`scripts/build-raylib.kt`), which downloads and builds raylib and bundles its headers and static libraries under `cinterop/`, and then builds the library. Apple targets can only be built on macOS, so on Linux and Windows remove `"macos_arm64"` from `[target.native] platforms` in `kargo.toml` first.

If [mise](https://mise.jdx.dev/) is installed, the same build can be run with:

```shell
mise run build
```

To publish the package to your local Maven repository:

```shell
mise run publishLocal
```

## License

`raylib-kt` is distributed under the [zlib/libpng license](LICENSE).
