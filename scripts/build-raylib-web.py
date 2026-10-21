#!/usr/bin/env python3
"""Builds raylib for the web (Emscripten, PLATFORM_WEB) and generates the Kotlin/Wasm bindings.

Steps:
  1. clone raylib (same version as scripts/build-raylib.kt) and emsdk under build/
  2. generate web/raylib_shim.c (scalar-only C wrappers) and src-wasm/RaylibBindings.kt from raylib_api.json
  3. compile raylib + the shim with emcc into build/web/dist/raylib.mjs + raylib.wasm (copied to examples/web/resources/)

Only the Python standard library, git and a C-capable host are needed; emsdk brings its own
Node, Python and LLVM. Pass --bindings-only to regenerate the generated files without emcc.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

RAYLIB_VERSION = "6.2"
EMSDK_VERSION = "4.0.23"

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "build"
RAYLIB_SRC = BUILD / "raylib" / RAYLIB_VERSION / "source"
EMSDK = BUILD / "emsdk"
SHIM_C = BUILD / "web" / "raylib_shim.c"
BINDINGS_KT = ROOT / "src-wasm" / "RaylibBindings.kt"
OUT_DIR = ROOT / "build" / "web" / "dist"
EXAMPLE_RESOURCES = ROOT / "examples" / "web" / "resources"

# Handwritten in src-wasm/Raylib.kt, because a blocking call cannot run in a browser tab.
HANDWRITTEN = {
    "WindowShouldClose",  # emscripten_sleep() needs ASYNCIFY, and the loop is driven by requestAnimationFrame
    "SetTargetFPS",  # WaitTime() would block the page; the browser paces frames
    "WaitTime",
}
KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while",
}
INT_TYPES = {
    "int", "short", "long", "char", "unsigned int", "unsigned short", "unsigned char", "unsigned long",
}


def run(*cmd, cwd=None, env=None):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], cwd=cwd, env=env, check=True)


# ---------------------------------------------------------------------------------------------
# Toolchain provisioning
# ---------------------------------------------------------------------------------------------

def clone_raylib():
    if (RAYLIB_SRC / "src" / "raylib.h").is_file():
        return
    if RAYLIB_SRC.exists():
        sys.exit(f"Incomplete raylib source directory at {RAYLIB_SRC}; delete it and run again")
    RAYLIB_SRC.parent.mkdir(parents=True, exist_ok=True)
    run("git", "clone", "--depth", "1", "--branch", RAYLIB_VERSION,
        "https://github.com/raysan5/raylib.git", RAYLIB_SRC.name, cwd=RAYLIB_SRC.parent)


def emscripten_config():
    return EMSDK / ".emscripten"


def provision_emsdk():
    if emscripten_config().is_file():
        return
    if not (EMSDK / "emsdk.py").is_file():
        EMSDK.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--depth", "1", "https://github.com/emscripten-core/emsdk.git", EMSDK.name,
            cwd=EMSDK.parent)
    run(sys.executable, "emsdk.py", "install", EMSDK_VERSION, cwd=EMSDK)
    run(sys.executable, "emsdk.py", "activate", EMSDK_VERSION, cwd=EMSDK)


def emcc_command():
    env = dict(os.environ)
    env["EM_CONFIG"] = str(emscripten_config())
    env["EMSDK"] = str(EMSDK)
    return [sys.executable, str(EMSDK / "upstream" / "emscripten" / "emcc.py")], env


# ---------------------------------------------------------------------------------------------
# Binding generation
# ---------------------------------------------------------------------------------------------

class Api:
    def __init__(self, data):
        self.structs = {s["name"]: s for s in data["structs"]}
        self.aliases = {a["name"]: a["type"] for a in data["aliases"]}
        self.enums = {e["name"] for e in data["enums"]}
        self.enum_values = [v for e in data["enums"] for v in e["values"]]
        self.functions = data["functions"]
        self.colors = [d for d in data["defines"] if d["type"] == "COLOR"]
        self.int_defines = [d for d in data["defines"] if d["type"] == "INT"]
        self._flat = {}

    def resolve(self, ctype):
        ctype = ctype.replace("const ", "").strip()
        while ctype in self.aliases:
            ctype = self.aliases[ctype]
        return ctype

    def kind(self, ctype):
        """Returns 'int', 'float', 'double', 'bool', 'ptr', 'struct' or None when unsupported."""
        t = self.resolve(ctype)
        if t in INT_TYPES or t in self.enums:
            return "int"
        if t in ("float", "double", "bool"):
            return t
        if t in self.structs:
            return "struct" if self.flatten(t) is not None else None
        if re.fullmatch(r"[\w ]+(\s*\*)+", t):
            return "ptr"
        return None

    def flatten(self, struct):
        """Flat list of (field path, c type, kind) for a struct, or None if it holds arrays."""
        if struct in self._flat:
            return self._flat[struct]
        self._flat[struct] = None
        out = []
        for field in self.structs[struct]["fields"]:
            if "[" in field["type"]:
                return None
            kind = self.kind(field["type"])
            if kind is None:
                return None
            if kind == "struct":
                inner = self.flatten(self.resolve(field["type"]))
                out += [([field["name"]] + p, t, k) for p, t, k in inner]
            else:
                out.append(([field["name"]], self.resolve(field["type"]) if kind != "ptr" else field["type"], kind))
        self._flat[struct] = out
        return out


def c_scalar_param(kind):
    return {"int": "int", "float": "float", "double": "double", "bool": "int", "ptr": "int"}[kind]


def kotlin_scalar(kind):
    return {"int": "Int", "float": "Float", "double": "Double", "bool": "Boolean", "ptr": "Int"}[kind]


def kt_name(name):
    return f"`{name}`" if name in KOTLIN_KEYWORDS else name


def struct_initializer(api, struct, prefix):
    """C brace initializer for a struct built from flattened scalar parameters."""
    parts = []
    for field in api.structs[struct]["fields"]:
        kind = api.kind(field["type"])
        name = f"{prefix}_{field['name']}"
        if kind == "struct":
            parts.append(struct_initializer(api, api.resolve(field["type"]), name))
        elif kind == "ptr":
            parts.append(f"({field['type']})(size_t){name}")
        elif kind == "bool":
            parts.append(f"{name} != 0")
        else:
            parts.append(f"({api.resolve(field['type'])}){name}")
    return "{ " + ", ".join(parts) + " }"


def analyse(api, fn):
    """Describes how a function crosses the JS boundary, or returns None when it cannot."""
    if fn["name"] in HANDWRITTEN:
        return None
    params = []
    for p in fn.get("params", []):
        t = p["type"]
        if t == "...":
            return None
        if t.strip() == "const char *":
            params.append((p["name"], t, "string"))
            continue
        kind = api.kind(t)
        if kind in (None, "ptr"):
            return None
        params.append((p["name"], t, kind))
    ret = fn["returnType"]
    if ret == "void":
        rkind = "void"
    elif ret.strip() == "const char *":
        rkind = "string"
    else:
        rkind = api.kind(ret)
        if rkind in (None, "ptr"):
            return None
    return params, rkind


def flat_params(api, params):
    """[(shim parameter name, c scalar type, kotlin expression kind, kotlin accessor path)]"""
    flat = []
    for name, ctype, kind in params:
        if kind == "struct":
            for path, _, k in api.flatten(api.resolve(ctype)):
                flat.append(("_".join([name] + path), k, ".".join([kt_name(name)] + path)))
        elif kind == "string":
            flat.append((name, "string", kt_name(name)))
        else:
            flat.append((name, kind, kt_name(name)))
    return flat


def generate_c(api, functions):
    lines = [
        "// Generated by scripts/build-raylib-web.py from raylib_api.json. Do not edit.",
        "// Every wrapper takes and returns scalars only: the wasm32 C ABI passes structs by pointer, which",
        "// the Kotlin/Wasm module cannot build in the Emscripten heap on every call.",
        "#include <emscripten.h>",
        "#include <stddef.h>",
        '#include "raylib.h"',
        "",
        "static double rkt_ret[64];",
        "EMSCRIPTEN_KEEPALIVE double rkt_ret_at(int index) { return rkt_ret[index]; }",
        "",
    ]
    exported = ["_rkt_ret_at", "_malloc", "_free"]
    for fn, (params, rkind) in functions:
        flat = flat_params(api, params)
        c_params = ", ".join(
            ("const char *" if k == "string" else c_scalar_param(k)) + f" {n}" for n, k, _ in flat
        ) or "void"
        shim = f"rkt_{fn['name']}"
        if rkind == "void" or rkind == "struct":
            c_ret = "void"
        elif rkind == "string":
            c_ret = "const char *"
        else:
            c_ret = c_scalar_param(rkind)
        body = []
        args = []
        for name, ctype, kind in params:
            if kind == "struct":
                struct = api.resolve(ctype)
                body.append(f"    {struct} {name} = {struct_initializer(api, struct, name)};")
                args.append(name)
            elif kind == "bool":
                args.append(f"{name} != 0")
            elif kind == "string":
                args.append(name)
            else:
                args.append(f"({api.resolve(ctype)}){name}")
        call = f"{fn['name']}({', '.join(args)})"
        if rkind == "void":
            body.append(f"    {call};")
        elif rkind == "struct":
            struct = api.resolve(fn["returnType"])
            body.append(f"    {struct} result = {call};")
            for i, (path, _, k) in enumerate(api.flatten(struct)):
                value = "result." + ".".join(path)
                body.append(f"    rkt_ret[{i}] = (double){'(size_t)' if k == 'ptr' else ''}{value};")
        elif rkind == "string":
            body.append(f"    return {call};")
        else:
            body.append(f"    return ({c_ret}){call};")
        lines.append(f"EMSCRIPTEN_KEEPALIVE {c_ret} {shim}({c_params})\n{{")
        lines += body
        lines.append("}\n")
        exported.append(f"_{shim}")
    return "\n".join(lines), exported


def js_args(flat):
    out = []
    for n, k, _ in flat:
        out.append(n)
    return out


def generate_kotlin(api, functions):
    lines = [
        "// Generated by scripts/build-raylib-web.py from raylib_api.json. Do not edit.",
        "@file:OptIn(ExperimentalWasmJsInterop::class)",
        "@file:Suppress(\"unused\", \"FunctionName\", \"ObjectPropertyName\", \"ConstPropertyName\")",
        "",
        "package raylib",
        "",
        "import kotlin.js.ExperimentalWasmJsInterop",
        "",
        '@JsFun("(index) => raylib._rkt_ret_at(index)")',
        "private external fun jsRet(index: Int): Double",
        "",
    ]
    # structs
    for name, s in api.structs.items():
        flat = api.flatten(name)
        if flat is None:
            continue
        fields = []
        for field in s["fields"]:
            kind = api.kind(field["type"])
            type_name = field["type"] if kind == "struct" else kotlin_scalar(kind)
            if kind == "struct":
                type_name = api.resolve(field["type"])
            default = " = 255" if (name == "Color" and field["name"] == "a") else ""
            fields.append(f"    var {kt_name(field['name'])}: {type_name}{default}")
        lines.append(f"/** {s['description']} */")
        lines.append(f"data class {name}(\n" + ",\n".join(fields) + "\n)\n")
    for alias, target in api.aliases.items():
        resolved = api.resolve(target)
        if resolved in api.structs and api.flatten(resolved) is not None:
            lines.append(f"typealias {alias} = {resolved}")
    lines.append("")
    # colors
    for d in api.colors:
        r, g, b, a = [int(x) for x in re.findall(r"\d+", d["value"].split("{")[1])]
        lines.append(f"val {d['name']}: Color get() = Color({r}, {g}, {b}, {a})")
    lines.append("")
    # enum values and numeric defines
    for v in api.enum_values:
        lines.append(f"const val {v['name']}: Int = {v['value']}")
    lines.append("")

    def read_struct(struct, base):
        """Kotlin expression building `struct` from rkt_ret starting at index `base`."""
        flat = api.flatten(struct)
        index = [base]

        def build(sname):
            args = []
            for field in api.structs[sname]["fields"]:
                kind = api.kind(field["type"])
                if kind == "struct":
                    args.append(build(api.resolve(field["type"])))
                else:
                    args.append(convert(kind, f"jsRet({index[0]})"))
                    index[0] += 1
            return f"{sname}({', '.join(args)})"

        return build(struct)

    def convert(kind, expr):
        if kind == "float":
            return f"{expr}.toFloat()"
        if kind == "double":
            return expr
        if kind == "bool":
            return f"({expr} != 0.0)"
        return f"{expr}.toLong().toInt()"

    for fn, (params, rkind) in functions:
        flat = flat_params(api, params)
        js_names = [n for n, _, _ in flat]
        converted = []
        for n, k, _ in flat:
            converted.append({"string": "__s_" + n, "bool": f"({n} ? 1 : 0)"}.get(k, n))
        shim_args = ", ".join(converted)
        pre = "".join(f"const __s_{n} = raylib.stringToNewUTF8({n}); " for n, k, _ in flat if k == "string")
        post = "".join(f" raylib._free(__s_{n});" for n, k, _ in flat if k == "string")
        call = f"raylib._rkt_{fn['name']}({shim_args})"
        if rkind == "string":
            js_body = f"{{ {pre}const __p = {call};{post} return __p ? raylib.UTF8ToString(__p) : ''; }}"
        elif rkind == "bool":
            js_body = f"{{ {pre}const __r = !!{call};{post} return __r; }}"
        elif rkind in ("int", "float", "double"):
            js_body = f"{{ {pre}const __r = {call};{post} return __r; }}"
        else:
            js_body = f"{{ {pre}{call};{post} }}"
        js = f"({', '.join(js_names)}) => {js_body}"
        js = js.replace("\\", "\\\\").replace('"', '\\"')

        ext_params = ", ".join(
            f"{n}: " + ("String" if k == "string" else kotlin_scalar(k)) for n, k, _ in flat
        )
        if rkind == "string":
            ext_ret = ": String"
        elif rkind in ("void", "struct"):
            ext_ret = ""
        else:
            ext_ret = f": {kotlin_scalar(rkind)}"
        ext_name = f"js_{fn['name']}"
        lines.append(f'@JsFun("{js}")')
        lines.append(f"private external fun {ext_name}({ext_params}){ext_ret}")

        params_kt = ", ".join(
            f"{kt_name(n)}: " + ("String" if k == "string" else (api.resolve(t) if k == "struct" else kotlin_scalar(k)))
            for n, t, k in params
        )
        call_args = ", ".join(path for _, _, path in flat)
        doc = fn["description"]
        lines.append(f"/** {doc} */")
        if rkind == "void":
            lines.append(f"fun {fn['name']}({params_kt}) {{ {ext_name}({call_args}) }}\n")
        elif rkind == "struct":
            struct = api.resolve(fn["returnType"])
            lines.append(
                f"fun {fn['name']}({params_kt}): {struct} {{\n    {ext_name}({call_args})\n"
                f"    return {read_struct(struct, 0)}\n}}\n"
            )
        else:
            ret = "String" if rkind == "string" else kotlin_scalar(rkind)
            lines.append(f"fun {fn['name']}({params_kt}): {ret} = {ext_name}({call_args})\n")
    return "\n".join(lines)


def generate_bindings():
    data = json.loads((RAYLIB_SRC / "tools/rlparser/output/raylib_api.json").read_text(encoding="utf-8"))
    api = Api(data)
    functions = []
    for fn in api.functions:
        analysed = analyse(api, fn)
        if analysed is not None:
            functions.append((fn, analysed))
    skipped = len(api.functions) - len(functions)
    print(f"Binding {len(functions)} of {len(api.functions)} raylib functions ({skipped} need pointers, callbacks or varargs)")

    c_source, exported = generate_c(api, functions)
    SHIM_C.parent.mkdir(parents=True, exist_ok=True)
    SHIM_C.write_text(c_source, encoding="utf-8", newline="\n")
    BINDINGS_KT.parent.mkdir(parents=True, exist_ok=True)
    BINDINGS_KT.write_text(generate_kotlin(api, functions), encoding="utf-8", newline="\n")
    return exported


# ---------------------------------------------------------------------------------------------
# raylib + shim -> raylib.mjs / raylib.wasm
# ---------------------------------------------------------------------------------------------

def build_web(exported):
    provision_emsdk()
    emcc, env = emcc_command()
    src = RAYLIB_SRC / "src"
    objects = BUILD / "web" / "objects"
    objects.mkdir(parents=True, exist_ok=True)

    cflags = [
        "-Os", "-std=gnu99", "-DPLATFORM_WEB", "-DGRAPHICS_API_OPENGL_ES2", "-D_DEFAULT_SOURCE",
        "-Wno-missing-braces", "-Wno-unused-value",
        f"-I{src}", f"-I{src / 'external' / 'glfw' / 'include'}",
    ]
    object_files = []
    sources = [(name, src / f"{name}.c") for name in ("rcore", "rshapes", "rtextures", "rtext", "rmodels", "raudio")]
    sources.append(("raylib_shim", SHIM_C))
    for name, source in sources:
        target = objects / f"{name}.o"
        run(*emcc, *cflags, "-c", source, "-o", target, env=env)
        object_files.append(target)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    run(
        *emcc, *object_files,
        "-o", OUT_DIR / "raylib.mjs",
        "-sUSE_GLFW=3",
        "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sEXPORT_NAME=createRaylib",
        "-sENVIRONMENT=web",
        "-sALLOW_MEMORY_GROWTH=1",
        "-sFORCE_FILESYSTEM=1",
        f"-sEXPORTED_FUNCTIONS={','.join(exported)}",
        "-sEXPORTED_RUNTIME_METHODS=FS,UTF8ToString,stringToNewUTF8",
        "-Os",
        env=env,
    )
    print(f"Wrote {OUT_DIR / 'raylib.mjs'} and {OUT_DIR / 'raylib.wasm'}")
    if EXAMPLE_RESOURCES.is_dir():
        for artifact in ("raylib.mjs", "raylib.wasm"):
            shutil.copy2(OUT_DIR / artifact, EXAMPLE_RESOURCES / artifact)


def main():
    clone_raylib()
    exported = generate_bindings()
    if "--bindings-only" not in sys.argv:
        build_web(exported)


if __name__ == "__main__":
    main()
