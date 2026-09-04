import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val RAYLIB_VERSION = "6.0"
private const val LINUX_RELEASE_SHA256 = "b64ba618a19e7da9e9c0e09bb398ecfd477a77d2d7231901bafc8739d27c08d2"
private const val LINUX_BUILD_CONFIGURATION = "raylib-6.0-linux-x64-glfw-x11-wayland-v2"
private const val MACOS_ARM64_BUILD_CONFIGURATION = "raylib-6.0-macos-arm64-glfw-cocoa-v2"
private const val MINGW_DEPENDENCY = "msys2-mingw-w64-x86_64-2"
private const val MINGW_DEPENDENCY_SHA256 = "50b7c3b4c91661753e2c23de00d4d7d113264c947f7ec6836eac085e16f602e8"
private val MACOS_FRAMEWORKS = listOf("OpenGL", "Cocoa", "IOKit", "CoreAudio", "CoreVideo")
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

@TaskAction
fun generateDef(
    @Input includeDir: Path,
    @Input bundledLibraryDir: Path,
    @Output defPath: Path,
    @Output raylibBuildDir: Path,
) {
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

    val linuxLibraryDir = bundledLibraryDir.resolve("linux")
    val mingwLibraryDir = bundledLibraryDir.resolve("mingw")
    val macosArm64LibraryDir = bundledLibraryDir.resolve("macosArm64")

    defPath.toFile().apply {
        parentFile.mkdirs()
        writeText(
            """
            headers = raylib.h raymath.h rlgl.h
            headerFilter = raylib.h raymath.h rlgl.h
            compilerOpts = -I${includeDir.forDefFile()}

            staticLibraries.linux = libraylib.a
            libraryPaths.linux = ${linuxLibraryDir.forDefFile()}

            staticLibraries.mingw = libraylib.a
            libraryPaths.mingw = ${mingwLibraryDir.forDefFile()}

            staticLibraries.osx = libraylib.a
            libraryPaths.osx = ${macosArm64LibraryDir.forDefFile()}

            linkerOpts.linux = -L/usr/lib64 -L/usr/lib/x86_64-linux-gnu -lm -lpthread -ldl -lrt -lX11 -lGL
            linkerOpts.mingw = -lopengl32 -lgdi32 -lwinmm
            linkerOpts.osx = ${MACOS_FRAMEWORKS.joinToString(" ") { "-framework $it" }}

            userSetupHint = raylib is built by the build-plugin with the Kotlin/Native LLVM toolchain
            """.trimIndent() + "\n"
        )
    }
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
    val llvm = newestDependency(dependencies, "llvm-", "-${hostName()}-essentials-")
    val executableSuffix = if (isWindows()) ".exe" else ""
    val clang = llvm.resolve("bin/clang$executableSuffix")
    val archiveTool = llvm.resolve("bin/llvm-ar$executableSuffix")

    check(clang.toFile().isFile && archiveTool.toFile().isFile) {
        "Kotlin/Native LLVM tools were not found under $llvm. Run a Kotlin/Native build once to download them."
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
        ?: Path.of(System.getProperty("user.home"), ".konan")
    return konanHome.resolve("dependencies")
}

private fun newestDependency(directory: Path, prefix: String, marker: String): Path {
    val candidates = directory.toFile().listFiles()
        ?.filter { it.isDirectory && it.name.startsWith(prefix) && it.name.contains(marker) }
        .orEmpty()
    return candidates.maxByOrNull { dependencyRevision(it.name) }?.toPath()
        ?: error("Required Kotlin/Native dependency matching '$prefix*$marker*' was not found in $directory")
}

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

private fun Path.forDefFile(): String = toAbsolutePath().normalize().toString().replace('\\', '/')

private fun Path.forCommandLine(): String = toAbsolutePath().normalize().toString().replace('\\', '/')

private fun run(workingDirectory: Path, vararg command: String) {
    val exitCode = ProcessBuilder(*command)
        .directory(workingDirectory.toFile())
        .inheritIO()
        .start()
        .waitFor()
    check(exitCode == 0) { "Command failed with exit code $exitCode: ${command.joinToString(" ")}" }
}
