/* kargo
*/
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val RAYLIB_VERSION = "6.2"
private const val LINUX_RELEASE_SHA256 = "586bbc32f3596d439382b1c5dbc6c1c573eb7e1d00fe92be7d4deb116883720d"
private const val LINUX_BUILD_CONFIGURATION = "raylib-6.2-linux-x64-glfw-x11-wayland-v2"
private const val MACOS_ARM64_BUILD_CONFIGURATION = "raylib-6.2-macos-arm64-glfw-cocoa-v2"
private const val MINGW_DEPENDENCY = "msys2-mingw-w64-x86_64-2"
private const val MINGW_DEPENDENCY_SHA256 = "50b7c3b4c91661753e2c23de00d4d7d113264c947f7ec6836eac085e16f602e8"
private val MACOS_FRAMEWORKS = listOf("OpenGL", "Cocoa", "IOKit", "CoreAudio", "CoreVideo", "QuartzCore")
private val RAYLIB_HEADERS = listOf("raylib.h", "raymath.h", "rlgl.h")
private val RAYLIB_SOURCES = listOf(
    "rcore",
    "rshapes",
    "rtextures",
    "rtext",
    "rmodels",
    "raudio",
    "rglfw",
)
private val WAYLAND_PROTOCOLS = listOf(
    "wayland.xml" to "wayland-client-protocol",
    "xdg-shell.xml" to "xdg-shell-client-protocol",
    "xdg-decoration-unstable-v1.xml" to "xdg-decoration-unstable-v1-client-protocol",
    "viewporter.xml" to "viewporter-client-protocol",
    "relative-pointer-unstable-v1.xml" to "relative-pointer-unstable-v1-client-protocol",
    "pointer-constraints-unstable-v1.xml" to "pointer-constraints-unstable-v1-client-protocol",
    "fractional-scale-v1.xml" to "fractional-scale-v1-client-protocol",
    "xdg-activation-v1.xml" to "xdg-activation-v1-client-protocol",
    "idle-inhibit-unstable-v1.xml" to "idle-inhibit-unstable-v1-client-protocol",
)

fun main() {
    val rootDir = Path.of("").toAbsolutePath()
    val includeDir = rootDir.resolve("cinterop/include")
    val bundledLibraryDir = rootDir.resolve("cinterop/lib")
    val raylibBuildDir = rootDir.resolve("build/raylib/$RAYLIB_VERSION")

    val sourceDir = raylibBuildDir.resolve("source")
    val host = hostPlatform()
    val nativeBuildDir = sourceDir.resolve("build")

    cloneRaylib(sourceDir)
    when (host) {
        "mingw" -> {
            buildIfMissing(sourceDir, nativeBuildDir.resolve("mingw"), "mingw")
            provisionLinuxRelease(sourceDir, nativeBuildDir.resolve("linux"))
        }
        "linux" -> {
            buildIfMissing(sourceDir, nativeBuildDir.resolve("linux"), "linux")
            buildIfMissing(sourceDir, nativeBuildDir.resolve("mingw"), "mingw")
        }
        "macosArm64" -> {
            provisionLinuxRelease(sourceDir, nativeBuildDir.resolve("linux"))
            buildIfMissing(sourceDir, nativeBuildDir.resolve("mingw"), "mingw")
            val macosLibraryDir = nativeBuildDir.resolve("macosArm64")
            buildIfMissing(sourceDir, macosLibraryDir, "macosArm64")
            verifyMacosArm64Library(sourceDir.resolve("src"), macosLibraryDir.resolve("libraylib.a"))
        }
    }

    val builtPlatforms = listOf("linux", "mingw") + listOfNotNull(host.takeIf { it == "macosArm64" })
    copyArtifacts(sourceDir, nativeBuildDir, includeDir, bundledLibraryDir, builtPlatforms)
}

private fun copyArtifacts(
    sourceDir: Path,
    nativeBuildDir: Path,
    includeDir: Path,
    bundledLibraryDir: Path,
    platforms: List<String>,
) {
    Files.createDirectories(includeDir)

    for (header in RAYLIB_HEADERS) {
        Files.copy(
            sourceDir.resolve("src").resolve(header),
            includeDir.resolve(header),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
    for (platform in platforms) {
        val platformLibraryDir = bundledLibraryDir.resolve(platform)
        Files.createDirectories(platformLibraryDir)
        Files.copy(
            nativeBuildDir.resolve(platform).resolve("libraylib.a"),
            platformLibraryDir.resolve("libraylib.a"),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

private fun buildIfMissing(sourceDir: Path, libraryDir: Path, platform: String) {
    val library = libraryDir.resolve("libraylib.a")
    val configurationFile = libraryDir.resolve(".build-configuration")
    val expectedConfiguration = when (platform) {
        "linux" -> LINUX_BUILD_CONFIGURATION
        "macosArm64" -> MACOS_ARM64_BUILD_CONFIGURATION
        else -> null
    }
    val configurationChanged = expectedConfiguration != null &&
        (!configurationFile.toFile().isFile || Files.readString(configurationFile).trim() != expectedConfiguration)

    if (!library.toFile().isFile || configurationChanged) {
        buildRaylib(sourceDir, libraryDir, platform)
        if (expectedConfiguration != null) Files.writeString(configurationFile, "$expectedConfiguration\n")
    }
}

private fun provisionLinuxRelease(sourceDir: Path, libraryDir: Path) {
    val targetLibrary = libraryDir.resolve("libraylib.a")
    if (targetLibrary.toFile().isFile) return

    val downloadsDir = sourceDir.resolve("build/downloads")
    val archive = downloadsDir.resolve("raylib-$RAYLIB_VERSION-linux-amd64.tar.gz")
    Files.createDirectories(downloadsDir)

    if (!archive.toFile().isFile || sha256(archive) != LINUX_RELEASE_SHA256) {
        val partialArchive = archive.resolveSibling("${archive.fileName}.part")
        Files.deleteIfExists(partialArchive)
        java.net.URI(
            "https://github.com/raysan5/raylib/releases/download/$RAYLIB_VERSION/" +
                "raylib-${RAYLIB_VERSION}_linux_amd64.tar.gz"
        ).toURL().openStream().use { input ->
            Files.copy(input, partialArchive, StandardCopyOption.REPLACE_EXISTING)
        }
        check(sha256(partialArchive) == LINUX_RELEASE_SHA256) {
            "Checksum mismatch while downloading the raylib $RAYLIB_VERSION Linux release"
        }
        Files.move(partialArchive, archive, StandardCopyOption.REPLACE_EXISTING)
    }

    val extractedDir = sourceDir.resolve("build/releases/linux")
    Files.createDirectories(extractedDir)
    run(sourceDir, "tar", "-xzf", archive.toString(), "-C", extractedDir.toString())
    val releaseLibrary = extractedDir.resolve("raylib-${RAYLIB_VERSION}_linux_amd64/lib/libraylib.a")
    check(releaseLibrary.toFile().isFile) { "The raylib Linux release did not contain libraylib.a" }
    Files.createDirectories(libraryDir)
    Files.copy(releaseLibrary, targetLibrary, StandardCopyOption.REPLACE_EXISTING)
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun cloneRaylib(sourceDir: Path) {
    if (sourceDir.resolve("src/raylib.h").toFile().isFile) return

    check(!sourceDir.toFile().exists()) {
        "Incomplete raylib source directory at $sourceDir; delete it and run the build again"
    }
    sourceDir.parent.toFile().mkdirs()
    run(
        sourceDir.parent,
        "git",
        "clone",
        "--depth", "1",
        "--branch", RAYLIB_VERSION,
        "https://github.com/raysan5/raylib.git",
        sourceDir.fileName.toString(),
    )
}

private fun buildRaylib(sourceDir: Path, libraryDir: Path, platform: String) {
    val dependencies = konanDependenciesDir()
    // Kotlin/Native does not ship an "essentials" LLVM bundle for macOS hosts,
    // so use the Xcode toolchain there, which is already required for the SDK.
    val (clang, archiveTool) = if (platform == "macosArm64") {
        xcrunTool("clang") to xcrunTool("ar")
    } else {
        val llvm = konanLlvm(dependencies)
        val executableSuffix = if (isWindows()) ".exe" else ""
        val clang = llvm.resolve("bin/clang$executableSuffix")
        val archiveTool = llvm.resolve("bin/llvm-ar$executableSuffix")
        check(clang.toFile().isFile && archiveTool.toFile().isFile) {
            "Kotlin/Native LLVM tools were not found under $llvm. Run `kargo toolchain install` to download them."
        }
        clang to archiveTool
    }

    val sourceRoot = sourceDir.resolve("src")
    val objectDir = libraryDir.resolve("objects")
    objectDir.toFile().mkdirs()

    if (platform == "linux") generateWaylandProtocols(sourceRoot)

    val platformFlags = when (platform) {
        "mingw" -> {
            val sysroot = provisionMingwDependency(dependencies)
            listOf(
                "--target=x86_64-pc-windows-gnu",
                "--sysroot=${sysroot.forCommandLine()}",
                "-DUNICODE",
            )
        }
        "linux" -> {
            val toolchain = newestDependency(
                dependencies,
                "x86_64-unknown-linux-gnu-gcc-",
                "",
            )
            val sysroot = toolchain.resolve("x86_64-unknown-linux-gnu/sysroot")
            check(sysroot.resolve("usr/include/features.h").toFile().isFile) {
                "Kotlin/Native Linux sysroot was not found under $sysroot"
            }
            listOf(
                "--target=x86_64-unknown-linux-gnu",
                "--sysroot=${sysroot.forCommandLine()}",
                // The Kotlin/Native sysroot provides glibc while the host provides
                // the desktop-protocol headers that are not part of that sysroot.
                "-idirafter", "/usr/include",
                "-fPIC",
                "-D_GLFW_X11",
                "-D_GLFW_WAYLAND",
            )
        }
        "macosArm64" -> listOf(
            "--target=arm64-apple-macos11",
            "-isysroot", macosSdkPath().forCommandLine(),
            "-fPIC",
        )
        else -> error("Unsupported host platform: $platform")
    }

    val commonFlags = listOf(
        "-std=c99",
        "-O1",
        "-D_GNU_SOURCE",
        "-DPLATFORM_DESKTOP_GLFW",
        "-DGRAPHICS_API_OPENGL_33",
        "-Wno-missing-braces",
        "-fno-strict-aliasing",
        "-I${sourceRoot.forCommandLine()}",
        "-I${sourceRoot.resolve("external/glfw/include").forCommandLine()}",
    )

    val objects = RAYLIB_SOURCES.map { source ->
        val objectFile = objectDir.resolve("$source.o")
        val sourceFlags = if (platform == "macosArm64" && source == "rglfw") {
            arrayOf("-x", "objective-c", "-U_GNU_SOURCE")
        } else {
            emptyArray()
        }
        run(
            sourceRoot,
            clang.toString(),
            *platformFlags.toTypedArray(),
            *commonFlags.toTypedArray(),
            *sourceFlags,
            "-c", sourceRoot.resolve("$source.c").toString(),
            "-o", objectFile.toString(),
        )
        objectFile
    }

    libraryDir.toFile().mkdirs()
    run(
        sourceRoot,
        archiveTool.toString(),
        "rcs",
        libraryDir.resolve("libraylib.a").toString(),
        *objects.map(Path::toString).toTypedArray(),
    )

}

private fun verifyMacosArm64Library(sourceRoot: Path, library: Path) {
    val sdk = macosSdkPath()
    val smokeSource = library.parent.resolve("link-smoke-test.c")
    val smokeExecutable = library.parent.resolve("link-smoke-test")
    Files.writeString(
        smokeSource,
        "#include \"raylib.h\"\n" +
            "int main(void) { InitWindow(1, 1, \"\"); InitAudioDevice(); CloseAudioDevice(); CloseWindow(); return 0; }\n",
    )

    run(
        sourceRoot,
        "xcrun", "--sdk", "macosx", "clang",
        "--target=arm64-apple-macos11",
        "-isysroot", sdk.forCommandLine(),
        "-I${sourceRoot.forCommandLine()}",
        smokeSource.toString(),
        library.toString(),
        *MACOS_FRAMEWORKS.flatMap { listOf("-framework", it) }.toTypedArray(),
        "-o", smokeExecutable.toString(),
    )
    run(sourceRoot, "xcrun", "lipo", smokeExecutable.toString(), "-verify_arch", "arm64")
}

private fun provisionMingwDependency(dependencies: Path): Path {
    val dependencyDir = dependencies.resolve(MINGW_DEPENDENCY)
    val windowsHeader = dependencyDir.resolve("x86_64-w64-mingw32/include/windows.h")
    if (windowsHeader.toFile().isFile) return dependencyDir

    check(!dependencyDir.toFile().exists()) {
        "Incomplete Kotlin/Native MinGW dependency at $dependencyDir; delete it and run the build again"
    }

    val cacheDir = dependencies.resolve("cache")
    val archive = cacheDir.resolve("$MINGW_DEPENDENCY.tar.gz")
    Files.createDirectories(cacheDir)

    if (!archive.toFile().isFile || sha256(archive) != MINGW_DEPENDENCY_SHA256) {
        val partialArchive = archive.resolveSibling("${archive.fileName}.part")
        Files.deleteIfExists(partialArchive)
        java.net.URI(
            "https://download.jetbrains.com/kotlin/native/$MINGW_DEPENDENCY.tar.gz"
        ).toURL().openStream().use { input ->
            Files.copy(input, partialArchive, StandardCopyOption.REPLACE_EXISTING)
        }
        check(sha256(partialArchive) == MINGW_DEPENDENCY_SHA256) {
            "Checksum mismatch while downloading Kotlin/Native's MinGW dependency"
        }
        Files.move(partialArchive, archive, StandardCopyOption.REPLACE_EXISTING)
    }

    Files.createDirectories(dependencies)
    run(dependencies, "tar", "-xzf", archive.toString(), "-C", dependencies.toString())
    check(windowsHeader.toFile().isFile) {
        "The Kotlin/Native MinGW dependency did not contain x86_64-w64-mingw32/include/windows.h"
    }
    return dependencyDir
}

private fun generateWaylandProtocols(sourceRoot: Path) {
    val protocolDir = sourceRoot.resolve("external/glfw/deps/wayland")

    for ((protocolFile, outputName) in WAYLAND_PROTOCOLS) {
        val protocol = protocolDir.resolve(protocolFile)
        check(protocol.toFile().isFile) { "Missing bundled Wayland protocol: $protocol" }
        run(
            sourceRoot,
            "wayland-scanner",
            "client-header",
            protocol.toString(),
            sourceRoot.resolve("$outputName.h").toString(),
        )
        run(
            sourceRoot,
            "wayland-scanner",
            "private-code",
            protocol.toString(),
            sourceRoot.resolve("$outputName-code.h").toString(),
        )
    }
}

private fun konanDependenciesDir(): Path {
    val konanHome = System.getenv("KONAN_DATA_DIR")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: kargoToolchainsDir().resolve("konan")
    return konanHome.resolve("dependencies")
}

private fun kargoToolchainsDir(): Path =
    Path.of(System.getenv("KARGO_HOME")?.takeIf(String::isNotBlank) ?: System.getProperty("user.home") + "/.kargo")
        .resolve("toolchains")

// Kotlin/Native uses the "essentials" LLVM bundle on Windows hosts and the "dev"
// bundle on Linux and macOS hosts; either one provides clang and llvm-ar.
private fun konanLlvm(dependencies: Path): Path {
    val markers = listOf("-${hostName()}-essentials-", "-${hostName()}-dev-")
    findDependency(dependencies, "llvm-", markers)?.let { return it }
    downloadKonanDependencies(dependencies)
    return findDependency(dependencies, "llvm-", markers)
        ?: error("Required Kotlin/Native LLVM matching 'llvm-*${markers.joinToString("*' or 'llvm-*")}*' was not found in $dependencies")
}

// Kotlin/Native downloads its dependencies lazily, the first time it links a
// program, so a fresh machine has none of them after `kargo toolchain install`.
private fun downloadKonanDependencies(dependencies: Path) {
    val toolchains = kargoToolchainsDir()
    val distribution = toolchains.resolve("kotlin-native").toFile().listFiles()
        ?.filter { it.resolve("bin").isDirectory }
        ?.maxByOrNull { it.name }
        ?: error("No Kotlin/Native distribution was found under ${toolchains.resolve("kotlin-native")}. Run `kargo toolchain install`.")
    val compiler = distribution.resolve(if (isWindows()) "bin/kotlinc-native.bat" else "bin/kotlinc-native")
    val target = when (hostPlatform()) {
        "mingw" -> "mingw_x64"
        "linux" -> "linux_x64"
        else -> "macos_arm64"
    }

    val workDir = Files.createTempDirectory("raylib-kt-konan-deps")
    try {
        val source = workDir.resolve("main.kt")
        Files.writeString(source, "fun main() {}\n")
        val process = ProcessBuilder(
            compiler.toString(),
            "-Xcheck-dependencies",
            "-target", target,
            "-o", workDir.resolve("main").toString(),
            source.toString(),
        )
            .directory(workDir.toFile())
            .inheritIO()
        process.environment()["KONAN_DATA_DIR"] = dependencies.parent.toString()
        process.environment()["JAVA_HOME"] = System.getProperty("java.home")
        // Only the downloaded dependencies matter; the probe program itself is discarded.
        process.start().waitFor()
    } finally {
        workDir.toFile().deleteRecursively()
    }
}

private fun findDependency(directory: Path, prefix: String, markers: List<String>): Path? =
    directory.toFile().listFiles()
        ?.filter { dir -> dir.isDirectory && dir.name.startsWith(prefix) && markers.any { dir.name.contains(it) } }
        ?.maxByOrNull { dependencyRevision(it.name) }
        ?.toPath()

private fun newestDependency(directory: Path, prefix: String, marker: String): Path =
    findDependency(directory, prefix, listOf(marker))
        ?: error("Required Kotlin/Native dependency matching '$prefix*$marker*' was not found in $directory")

private fun dependencyRevision(name: String): Int =
    name.substringAfterLast('-').toIntOrNull() ?: 0

private fun macosSdkPath(): Path {
    check(isMacos()) { "The macOS ARM64 raylib library must be built on macOS" }
    val process = ProcessBuilder("xcrun", "--sdk", "macosx", "--show-sdk-path")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0 && output.isNotEmpty()) {
        "Unable to locate the macOS SDK with xcrun: $output"
    }
    return Path.of(output)
}

private fun xcrunTool(name: String): Path {
    val process = ProcessBuilder("xcrun", "--sdk", "macosx", "--find", name)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0 && output.isNotEmpty()) {
        "Unable to locate `$name` with xcrun: $output"
    }
    return Path.of(output)
}

private fun hostPlatform(): String = when {
    isWindows() -> "mingw"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    isMacos() -> "macosArm64"
    else -> error("raylib-kt currently supports Windows, Linux, and macOS hosts")
}

private fun hostName(): String = when {
    isWindows() -> "windows"
    isMacos() -> "macos"
    else -> "linux"
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

private fun isMacos(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

private fun Path.forCommandLine(): String = toAbsolutePath().normalize().toString().replace('\\', '/')

private fun run(workingDirectory: Path, vararg command: String) {
    val exitCode = ProcessBuilder(*command)
        .directory(workingDirectory.toFile())
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "Command failed with exit code $exitCode: ${command.joinToString(" ")}" }
}
