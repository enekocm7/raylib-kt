# raylib-kt

Kotlin/Native bindings for [raylib 6.0](https://www.raylib.com/).

Supported platforms:

- Linux x86-64 (`linuxX64`)
- Windows x86-64 (`mingwX64`)

## Install

### Gradle Kotlin Multiplatform

Add the dependency to your native source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.enekocm7:raylib-kt:0.2.0")
        }
    }
}
```

### Kotlin Toolchain

Add the dependency to your `module.yaml`:

```yaml
dependencies:
  - io.github.enekocm7:raylib-kt:0.2.0
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

## Requirements

The published package includes the raylib static libraries, so you do not need to install raylib separately.

The included `kotlin` and `kotlin.bat` wrappers download the required Kotlin Toolchain, JRE, Kotlin/Native compiler, and native toolchains automatically.

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

## Build from source

Clone the repository:

```shell
git clone https://github.com/enekocm7/raylib-kt.git
cd raylib-kt
```

Build on Linux:

```shell
./kotlin task :raylib-kt:generate@build-plugin
./kotlin build
```

Build on Windows:

```batch
kotlin.bat task :raylib-kt:generate@build-plugin
kotlin.bat build
```

The first command downloads and builds raylib, then generates the Kotlin/Native cinterop definition. The second command builds the library for Linux and Windows.

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
