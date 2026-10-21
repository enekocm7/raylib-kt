/* kargo
[dependencies]
"org.jetbrains.kotlinx:kotlinx-serialization-json-jvm" = "1.8.1"
*/
// Builds raylib for the web (Emscripten, PLATFORM_WEB) and generates the Kotlin/Wasm bindings.
//
//   1. clone raylib (same version as scripts/build-raylib.kt) and a pinned emsdk under build/
//   2. generate build/web/raylib_shim.c (scalar-only C wrappers) and src-wasm/RaylibBindings.kt
//      from raylib_api.json
//   3. compile raylib + the shim with emcc into build/web/dist/raylib.mjs + raylib.wasm
//      (copied to examples/web/resources/)
//
// emsdk brings its own Node, LLVM and (on Windows) Python; on Linux/macOS it needs python3 and git.
// Pass --bindings-only to regenerate the generated files without running emcc.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val RAYLIB_VERSION = "6.2"
private const val EMSDK_VERSION = "4.0.23"

// Handwritten in src-wasm/Raylib.kt, because a blocking call cannot run in a browser tab.
private val HANDWRITTEN = setOf(
    "WindowShouldClose", // emscripten_sleep() needs ASYNCIFY, and the loop is driven by requestAnimationFrame
    "SetTargetFPS", // WaitTime() would block the page; the browser paces frames
    "WaitTime",
)
private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
)
private val INT_TYPES = setOf(
    "int", "short", "long", "char", "unsigned int", "unsigned short", "unsigned char", "unsigned long",
)
private val RAYLIB_SOURCES = listOf("rcore", "rshapes", "rtextures", "rtext", "rmodels", "raudio")

private val rootDir: Path = Path.of("").toAbsolutePath()
private val buildDir: Path = rootDir.resolve("build")
private val raylibSource: Path = buildDir.resolve("raylib/$RAYLIB_VERSION/source")
private val emsdkDir: Path = buildDir.resolve("emsdk")
private val shimC: Path = buildDir.resolve("web/raylib_shim.c")
private val distDir: Path = buildDir.resolve("web/dist")
private val bindingsKt: Path = rootDir.resolve("src-wasm/RaylibBindings.kt")
private val exampleResources: Path = rootDir.resolve("examples/web/resources")

fun main(args: Array<String>) {
    cloneRaylib()
    val exported = generateBindings()
    if ("--bindings-only" !in args) buildWeb(exported)
}


private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")

private fun run(workingDirectory: Path, env: Map<String, String> = emptyMap(), vararg command: String) {
    println("+ " + command.joinToString(" ").take(300))
    val builder = ProcessBuilder(*command).directory(workingDirectory.toFile()).inheritIO()
    builder.environment().putAll(env)
    val exitCode = builder.start().waitFor()
    check(exitCode == 0) { "Command failed with exit code $exitCode: ${command.joinToString(" ")}" }
}

private fun cloneRaylib() {
    if (Files.isRegularFile(raylibSource.resolve("src/raylib.h"))) return
    check(!Files.exists(raylibSource)) {
        "Incomplete raylib source directory at $raylibSource; delete it and run the build again"
    }
    Files.createDirectories(raylibSource.parent)
    run(
        raylibSource.parent, emptyMap(),
        "git", "clone", "--depth", "1", "--branch", RAYLIB_VERSION,
        "https://github.com/raysan5/raylib.git", raylibSource.fileName.toString(),
    )
}

private fun emsdkExecutable(): String = emsdkDir.resolve(if (isWindows()) "emsdk.bat" else "emsdk").toString()

private fun provisionEmsdk() {
    if (Files.isRegularFile(emsdkDir.resolve(".emscripten"))) return
    if (!Files.isRegularFile(emsdkDir.resolve("emsdk.py"))) {
        Files.createDirectories(emsdkDir.parent)
        run(
            emsdkDir.parent, emptyMap(),
            "git", "clone", "--depth", "1", "https://github.com/emscripten-core/emsdk.git", emsdkDir.fileName.toString(),
        )
    }
    run(emsdkDir, emptyMap(), emsdkExecutable(), "install", EMSDK_VERSION)
    run(emsdkDir, emptyMap(), emsdkExecutable(), "activate", EMSDK_VERSION)
}

private fun emcc(): List<String> =
    listOf(emsdkDir.resolve("upstream/emscripten/emcc" + if (isWindows()) ".bat" else "").toString())

private fun emccEnv() = mapOf("EM_CONFIG" to emsdkDir.resolve(".emscripten").toString(), "EMSDK" to emsdkDir.toString())


private class Field(val type: String, val name: String)

private class Struct(val name: String, val description: String, val fields: List<Field>)

private class RlFunction(val name: String, val description: String, val returnType: String, val params: List<Field>)

private enum class Kind { INT, FLOAT, DOUBLE, BOOL, PTR, STRUCT, STRING }

/** A scalar leaf of a flattened struct: its field path, and its kind. */
private class Leaf(val path: List<String>, val kind: Kind)

private class Api(root: JsonObject) {
    val structs: Map<String, Struct> = root.getValue("structs").jsonArray.map { it.jsonObject }.associate { s ->
        val name = s.str("name")
        name to Struct(name, s.str("description"), s.getValue("fields").jsonArray.map { f ->
            Field(f.jsonObject.str("type"), f.jsonObject.str("name"))
        })
    }
    val aliases: Map<String, String> = root.getValue("aliases").jsonArray.associate { it.jsonObject.str("name") to it.jsonObject.str("type") }
    val enums: Set<String> = root.getValue("enums").jsonArray.map { it.jsonObject.str("name") }.toSet()
    val enumValues: List<Pair<String, String>> = root.getValue("enums").jsonArray.flatMap { e ->
        e.jsonObject.getValue("values").jsonArray.map { it.jsonObject.str("name") to it.jsonObject.str("value") }
    }
    val functions: List<RlFunction> = root.getValue("functions").jsonArray.map { f ->
        val o = f.jsonObject
        RlFunction(
            o.str("name"), o.str("description"), o.str("returnType"),
            (o["params"] as? JsonArray)?.map { Field(it.jsonObject.str("type"), it.jsonObject.str("name")) }.orEmpty(),
        )
    }
    val colors: List<Pair<String, List<Int>>> = root.getValue("defines").jsonArray.map { it.jsonObject }
        .filter { it.str("type") == "COLOR" }
        .map { d ->
            d.str("name") to Regex("\\d+").findAll(d.str("value").substringAfter("{")).map { it.value.toInt() }.toList()
        }
    private val flatCache = HashMap<String, List<Leaf>?>()

    fun resolve(ctype: String): String {
        var t = ctype.replace("const ", "").trim()
        while (t in aliases) t = aliases.getValue(t)
        return t
    }

    /** The kind of a C type, or null when it cannot cross the JS boundary. */
    fun kind(ctype: String): Kind? {
        val t = resolve(ctype)
        return when {
            t in INT_TYPES || t in enums -> Kind.INT
            t == "float" -> Kind.FLOAT
            t == "double" -> Kind.DOUBLE
            t == "bool" -> Kind.BOOL
            t in structs -> if (flatten(t) != null) Kind.STRUCT else null
            Regex("[\\w ]+(\\s*\\*)+").matches(t) -> Kind.PTR
            else -> null
        }
    }

    /** Flat scalar leaves of a struct, or null if it holds arrays or unsupported fields. */
    fun flatten(struct: String): List<Leaf>? {
        if (struct in flatCache) return flatCache[struct]
        flatCache[struct] = null
        val out = ArrayList<Leaf>()
        for (field in structs.getValue(struct).fields) {
            if ('[' in field.type) return null
            val kind = kind(field.type) ?: return null
            if (kind == Kind.STRUCT) {
                flatten(resolve(field.type))!!.mapTo(out) { Leaf(listOf(field.name) + it.path, it.kind) }
            } else {
                out += Leaf(listOf(field.name), kind)
            }
        }
        flatCache[struct] = out
        return out
    }
}

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

private class Param(val name: String, val ctype: String, val kind: Kind)

private class Binding(val fn: RlFunction, val params: List<Param>, val returnKind: Kind?)

/** A scalar crossing the boundary: shim parameter name, kind, Kotlin accessor path. */
private class FlatParam(val name: String, val kind: Kind, val path: String)

private fun ktName(name: String) = if (name in KOTLIN_KEYWORDS) "`$name`" else name

private fun cScalar(kind: Kind) = when (kind) {
    Kind.FLOAT -> "float"
    Kind.DOUBLE -> "double"
    else -> "int"
}

private fun ktScalar(kind: Kind) = when (kind) {
    Kind.FLOAT -> "Float"
    Kind.DOUBLE -> "Double"
    Kind.BOOL -> "Boolean"
    Kind.STRING -> "String"
    else -> "Int"
}

private fun analyse(api: Api, fn: RlFunction): Binding? {
    if (fn.name in HANDWRITTEN) return null
    val params = fn.params.map { p ->
        if (p.type == "...") return null
        if (p.type.trim() == "const char *") return@map Param(p.name, p.type, Kind.STRING)
        val kind = api.kind(p.type)
        if (kind == null || kind == Kind.PTR) return null
        Param(p.name, p.type, kind)
    }
    val returnKind: Kind? = when {
        fn.returnType == "void" -> null
        fn.returnType.trim() == "const char *" -> Kind.STRING
        else -> api.kind(fn.returnType).also { if (it == null || it == Kind.PTR) return null }
    }
    return Binding(fn, params, returnKind)
}

private fun flatParams(api: Api, params: List<Param>): List<FlatParam> = params.flatMap { p ->
    if (p.kind == Kind.STRUCT) {
        api.flatten(api.resolve(p.ctype))!!.map { FlatParam((listOf(p.name) + it.path).joinToString("_"), it.kind, (listOf(ktName(p.name)) + it.path).joinToString(".")) }
    } else {
        listOf(FlatParam(p.name, p.kind, ktName(p.name)))
    }
}

private fun structInitializer(api: Api, struct: String, prefix: String): String =
    api.structs.getValue(struct).fields.joinToString(", ", "{ ", " }") { field ->
        val name = "${prefix}_${field.name}"
        when (api.kind(field.type)) {
            Kind.STRUCT -> structInitializer(api, api.resolve(field.type), name)
            Kind.PTR -> "(${field.type})(size_t)$name"
            Kind.BOOL -> "$name != 0"
            else -> "(${api.resolve(field.type)})$name"
        }
    }

private fun generateC(api: Api, bindings: List<Binding>): Pair<String, List<String>> {
    val out = StringBuilder()
    out.append(
        """// Generated by scripts/build-raylib-web.kt from raylib_api.json. Do not edit.
// Every wrapper takes and returns scalars only: the wasm32 C ABI passes structs by pointer, which
// the Kotlin/Wasm module cannot build in the Emscripten heap on every call.
#include <emscripten.h>
#include <stddef.h>
#include "raylib.h"

static double rkt_ret[64];
EMSCRIPTEN_KEEPALIVE double rkt_ret_at(int index) { return rkt_ret[index]; }

""",
    )
    val exported = mutableListOf("_rkt_ret_at", "_malloc", "_free")
    for (b in bindings) {
        val flat = flatParams(api, b.params)
        val cParams = flat.joinToString(", ") { (if (it.kind == Kind.STRING) "const char *" else cScalar(it.kind)) + " ${it.name}" }
            .ifEmpty { "void" }
        val rk = b.returnKind
        val cReturn = if (rk == null || rk == Kind.STRUCT) "void" else if (rk == Kind.STRING) "const char *" else cScalar(rk)
        val body = mutableListOf<String>()
        val args = b.params.map { p ->
            when (p.kind) {
                Kind.STRUCT -> {
                    val struct = api.resolve(p.ctype)
                    body += "    $struct ${p.name} = ${structInitializer(api, struct, p.name)};"
                    p.name
                }
                Kind.BOOL -> "${p.name} != 0"
                Kind.STRING -> p.name
                else -> "(${api.resolve(p.ctype)})${p.name}"
            }
        }
        val call = "${b.fn.name}(${args.joinToString(", ")})"
        when (b.returnKind) {
            null -> body += "    $call;"
            Kind.STRUCT -> {
                val struct = api.resolve(b.fn.returnType)
                body += "    $struct result = $call;"
                api.flatten(struct)!!.forEachIndexed { i, leaf ->
                    val cast = if (leaf.kind == Kind.PTR) "(size_t)" else ""
                    body += "    rkt_ret[$i] = (double)$cast" + "result." + leaf.path.joinToString(".") + ";"
                }
            }
            Kind.STRING -> body += "    return $call;"
            else -> body += "    return ($cReturn)$call;"
        }
        out.append("EMSCRIPTEN_KEEPALIVE $cReturn rkt_${b.fn.name}($cParams)\n{\n")
        body.forEach { out.append(it).append('\n') }
        out.append("}\n\n")
        exported += "_rkt_${b.fn.name}"
    }
    return out.toString() to exported
}

private fun generateKotlin(api: Api, bindings: List<Binding>): String {
    val out = StringBuilder()
    // Kargo scans scripts for top-level `@file:`, `package` and `import` lines, so none of them may start a source line.
    out.append(
        listOf(
            "// Generated by scripts/build-raylib-web.kt from raylib_api.json. Do not edit.",
            "@file:OptIn(ExperimentalWasmJsInterop::class)",
            "@file:Suppress(\"unused\", \"FunctionName\", \"ObjectPropertyName\", \"ConstPropertyName\")",
            "",
            "package raylib",
            "",
            "import kotlin.js.ExperimentalWasmJsInterop",
            "",
            "@JsFun(\"(index) => raylib._rkt_ret_at(index)\")",
            "private external fun jsRet(index: Int): Double",
            "",
            "",
        ).joinToString("\n"),
    )
    for ((name, s) in api.structs) {
        if (api.flatten(name) == null) continue
        val fields = s.fields.joinToString(",\n") { field ->
            val kind = api.kind(field.type)!!
            val type = if (kind == Kind.STRUCT) api.resolve(field.type) else ktScalar(kind)
            val default = if (name == "Color" && field.name == "a") " = 255" else ""
            "    var ${ktName(field.name)}: $type$default"
        }
        out.append("/** ${s.description} */\ndata class $name(\n$fields\n)\n\n")
    }
    for ((alias, target) in api.aliases) {
        val resolved = api.resolve(target)
        if (resolved in api.structs && api.flatten(resolved) != null) out.append("typealias $alias = $resolved\n")
    }
    out.append('\n')
    for ((name, c) in api.colors) out.append("val $name: Color get() = Color(${c.joinToString(", ")})\n")
    out.append('\n')
    for ((name, value) in api.enumValues) out.append("const val $name: Int = $value\n")
    out.append('\n')

    fun convert(kind: Kind, expr: String) = when (kind) {
        Kind.FLOAT -> "$expr.toFloat()"
        Kind.DOUBLE -> expr
        Kind.BOOL -> "($expr != 0.0)"
        else -> "$expr.toLong().toInt()"
    }

    fun readStruct(struct: String): String {
        var index = 0
        fun build(name: String): String = api.structs.getValue(name).fields.joinToString(", ", "$name(", ")") { field ->
            val kind = api.kind(field.type)!!
            if (kind == Kind.STRUCT) build(api.resolve(field.type)) else convert(kind, "jsRet(${index++})")
        }
        return build(struct)
    }

    for (b in bindings) {
        val flat = flatParams(api, b.params)
        val converted = flat.map {
            when (it.kind) {
                Kind.STRING -> "__s_${it.name}"
                Kind.BOOL -> "(${it.name} ? 1 : 0)"
                else -> it.name
            }
        }
        val pre = flat.filter { it.kind == Kind.STRING }.joinToString("") { "const __s_${it.name} = raylib.stringToNewUTF8(${it.name}); " }
        val post = flat.filter { it.kind == Kind.STRING }.joinToString("") { " raylib._free(__s_${it.name});" }
        val call = "raylib._rkt_${b.fn.name}(${converted.joinToString(", ")})"
        val jsBody = when (b.returnKind) {
            Kind.STRING -> "{ ${pre}const __p = $call;$post return __p ? raylib.UTF8ToString(__p) : ''; }"
            Kind.BOOL -> "{ ${pre}const __r = !!$call;$post return __r; }"
            Kind.INT, Kind.FLOAT, Kind.DOUBLE -> "{ ${pre}const __r = $call;$post return __r; }"
            else -> "{ $pre$call;$post }"
        }
        val js = "(${flat.joinToString(", ") { it.name }}) => $jsBody".replace("\\", "\\\\").replace("\"", "\\\"")
        val extParams = flat.joinToString(", ") { "${it.name}: ${ktScalar(it.kind)}" }
        val rk = b.returnKind
        val extReturn = if (rk == null || rk == Kind.STRUCT) "" else ": ${ktScalar(rk)}"
        val extName = "js_${b.fn.name}"
        out.append("@JsFun(\"$js\")\nprivate external fun $extName($extParams)$extReturn\n")

        val paramsKt = b.params.joinToString(", ") {
            "${ktName(it.name)}: " + if (it.kind == Kind.STRUCT) api.resolve(it.ctype) else ktScalar(it.kind)
        }
        val callArgs = flat.joinToString(", ") { it.path }
        out.append("/** ${b.fn.description} */\n")
        when (b.returnKind) {
            null -> out.append("fun ${b.fn.name}($paramsKt) { $extName($callArgs) }\n\n")
            Kind.STRUCT -> {
                val struct = api.resolve(b.fn.returnType)
                out.append("fun ${b.fn.name}($paramsKt): $struct {\n    $extName($callArgs)\n    return ${readStruct(struct)}\n}\n\n")
            }
            else -> out.append("fun ${b.fn.name}($paramsKt): ${ktScalar(rk)} = $extName($callArgs)\n\n")
        }
    }
    return out.toString()
}

private fun generateBindings(): List<String> {
    val json = Files.readString(raylibSource.resolve("tools/rlparser/output/raylib_api.json"))
    val api = Api(Json.parseToJsonElement(json).jsonObject)
    val bindings = api.functions.mapNotNull { analyse(api, it) }
    println(
        "Binding ${bindings.size} of ${api.functions.size} raylib functions " +
            "(${api.functions.size - bindings.size} need pointers, callbacks or varargs)",
    )

    val (cSource, exported) = generateC(api, bindings)
    Files.createDirectories(shimC.parent)
    Files.writeString(shimC, cSource)
    Files.createDirectories(bindingsKt.parent)
    Files.writeString(bindingsKt, generateKotlin(api, bindings).trimEnd() + "
")
    return exported
}


private fun buildWeb(exported: List<String>) {
    provisionEmsdk()
    val env = emccEnv()
    val src = raylibSource.resolve("src")
    val objects = buildDir.resolve("web/objects")
    Files.createDirectories(objects)

    val cflags = listOf(
        "-Os", "-std=gnu99", "-DPLATFORM_WEB", "-DGRAPHICS_API_OPENGL_ES2", "-D_DEFAULT_SOURCE",
        "-Wno-missing-braces", "-Wno-unused-value",
        "-I$src", "-I${src.resolve("external/glfw/include")}",
    )
    val sources = RAYLIB_SOURCES.map { it to src.resolve("$it.c") } + ("raylib_shim" to shimC)
    val objectFiles = sources.map { (name, source) ->
        val target = objects.resolve("$name.o")
        run(rootDir, env, *emcc().toTypedArray(), *cflags.toTypedArray(), "-c", source.toString(), "-o", target.toString())
        target.toString()
    }

    Files.createDirectories(distDir)
    // The export list is too long for cmd.exe's command line limit, so it goes in a file (emcc reads `@file`).
    val exportsFile = buildDir.resolve("web/exported_functions.txt")
    Files.writeString(exportsFile, exported.joinToString("\n"))
    run(
        rootDir, env,
        *emcc().toTypedArray(), *objectFiles.toTypedArray(),
        "-o", distDir.resolve("raylib.mjs").toString(),
        "-sUSE_GLFW=3",
        "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sEXPORT_NAME=createRaylib",
        "-sENVIRONMENT=web",
        "-sALLOW_MEMORY_GROWTH=1",
        "-sFORCE_FILESYSTEM=1",
        "-sEXPORTED_FUNCTIONS=@$exportsFile",
        "-sEXPORTED_RUNTIME_METHODS=FS,UTF8ToString,stringToNewUTF8",
        "-Os",
    )
    println("Wrote ${distDir.resolve("raylib.mjs")} and ${distDir.resolve("raylib.wasm")}")
    if (Files.isDirectory(exampleResources)) {
        for (artifact in listOf("raylib.mjs", "raylib.wasm")) {
            Files.copy(distDir.resolve(artifact), exampleResources.resolve(artifact), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
