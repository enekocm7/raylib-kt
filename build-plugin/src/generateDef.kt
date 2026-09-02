import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path

@TaskAction
fun generateDef(
    @Output defPath: Path,
) {
    val file = defPath.toFile()
    if (file.exists()) file.delete()

    file.createNewFile()
    val libPath = defPath.parent
    val fixedPath = libPath.toString().replace("\\", "/")
    val linuxPath = "$fixedPath/lib/linux"
    val windowsPath = "$fixedPath/lib/mingw"

    file.writeText(
        """
        headers = raylib.h raymath.h rlgl.h
        headerFilter = raylib.h raymath.h rlgl.h

        staticLibraries.linux = libraylib.a libraylib.so
        libraryPaths.linux = $linuxPath

        staticLibraries.mingw = libraylib.a raylib.dll libraylibdll.a
        libraryPaths.mingw = $windowsPath

        linkerOpts.linux = -lm -lpthread -ldl -lrt -lX11 -lGL
        linkerOpts.mingw = -lopengl32 -lgdi32 -lwinmm

        userSetupHint = The required raylib static library is bundled with this Kotlin l
    """.trimIndent()
    )

}