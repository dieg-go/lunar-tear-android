"""Generate the Kotlin data file holding the reference patcher's smali edits.

lunar-scripts/android/patch_apk.py applies four structural smali edits (plus a
string-replacement pass) to an apktool-decoded tree. The on-device patcher has to
apply exactly the same edits, and the way a faithful port goes wrong is a
retyped literal - a stray space, a missing blank line, a branch label that moved.
So the literals are not retyped: this script imports the reference, evaluates the
needles/replacements with ast, and emits them as Kotlin constants.

    python tools/gen-smali-patches.py [repo-root] [out-dir]

Regenerate whenever the reference changes; SmaliPatchesTest asserts that the
generated file still matches the reference when the repo is present.
"""

import ast
import importlib.util
import os
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\diego\lunar-scripts"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(
    sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        ROOT, "app", "src", "main", "java", "dev", "lunartear", "host", "patch", "apk"
    ),
    "SmaliPatchData.kt",
)

spec = importlib.util.spec_from_file_location(
    "patch_apk", os.path.join(REPO, "android", "patch_apk.py")
)
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)

tree = ast.parse(open(os.path.join(REPO, "android", "patch_apk.py"), encoding="utf-8").read())


def fold(node):
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        return fold(node.left) + fold(node.right)
    if isinstance(node, ast.List):
        return [fold(e) for e in node.elts]
    if isinstance(node, ast.Tuple):
        return tuple(fold(e) for e in node.elts)
    if isinstance(node, ast.Name) and node.id == "None":
        return None
    raise SystemExit(f"cannot fold {ast.dump(node)[:120]}")


functions = {n.name: n for n in tree.body if isinstance(n, ast.FunctionDef)}


def literal(name, target):
    fn = functions[name]
    for stmt in ast.walk(fn):
        if isinstance(stmt, ast.Assign) and isinstance(stmt.targets[0], ast.Name):
            if stmt.targets[0].id == target:
                return fold(stmt.value)
    raise SystemExit(f"{name}: no assignment to {target}")


def kotlin_string(value):
    """Emit a raw string, which is the only readable form for smali blocks."""
    if value.endswith("\n") and '"""' not in value and not value.endswith('"'):
        return '"""' + value.replace("$", "${'$'}") + '"""'
    # Fall back to an escaped regular string (no caller hits this today).
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
    )
    return f'"{escaped}"'


lines = [
    "package dev.lunartear.host.patch.apk",
    "",
    "// GENERATED FILE - do not edit by hand.",
    "//",
    "// Produced by tools/gen-smali-patches.py from",
    "// lunar-scripts/android/patch_apk.py: the needles and replacements below are",
    "// evaluated out of the reference's own source with ast, so the port cannot",
    "// drift from it by a retyped literal. Regenerate after changing the reference;",
    "// SmaliPatchesTest fails when this file and the reference disagree.",
    "",
    "/** The reference patcher's Facebook-SDK smali edits, verbatim. */",
    "internal object SmaliPatchData {",
    "",
    "    /** `SMALI_REPLACEMENTS`: a null replacement means the auth host. */",
    "    val STRING_REPLACEMENTS: List<Pair<String, String?>> = listOf(",
]

for old, new in ref.SMALI_REPLACEMENTS:
    lines.append(f"        {kotlin_string(old)} to {kotlin_string(new) if new is not None else 'null'},")
lines += [
    "    )",
    "",
    "    /** `SMALI_BASE_DOMAIN`: the base domain, replaced separately. */",
    f"    val BASE_DOMAIN: Pair<String, String?> = {kotlin_string(ref.SMALI_BASE_DOMAIN[0])} "
    f"to {kotlin_string(ref.SMALI_BASE_DOMAIN[1]) if ref.SMALI_BASE_DOMAIN[1] is not None else 'null'}",
    "",
    "    /** `bypass_cct_redirect_check` - getValidRedirectURI returns the default. */",
    f"    val CCT_REDIRECT_NEEDLE: String = {kotlin_string(literal('bypass_cct_redirect_check', 'needle'))}",
    f"    val CCT_REDIRECT_REPLACEMENT: String = {kotlin_string(literal('bypass_cct_redirect_check', 'replacement'))}",
    "",
    "    /** `patch_customtab_use_plain_view_intent` - whole openCustomTab body. */",
    f"    val OPEN_CUSTOM_TAB_NEEDLE: String = {kotlin_string(literal('patch_customtab_use_plain_view_intent', 'needle'))}",
    f"    val OPEN_CUSTOM_TAB_REPLACEMENT: String = {kotlin_string(literal('patch_customtab_use_plain_view_intent', 'replacement'))}",
    "",
    "    /** `force_webview_only_login_behavior` - NATIVE_WITH_FALLBACK's booleans. */",
    f"    val LOGIN_BEHAVIOR_NEEDLE: String = {kotlin_string(literal('force_webview_only_login_behavior', 'needle'))}",
    "    val LOGIN_BEHAVIOR_FLIPS: List<Pair<String, String>> = listOf(",
]
for old, new in literal("force_webview_only_login_behavior", "flips"):
    lines.append(f"        {kotlin_string(old)} to {kotlin_string(new)},")
lines += [
    "    )",
    "    /** The reference ends the block at the next `invoke-direct/range`. */",
    '    const val LOGIN_BEHAVIOR_BLOCK_END: String = "invoke-direct/range"',
    "",
    "    /** `patch_webdialog_request_overload` - the added API-24 override. */",
    f"    val WEBDIALOG_SIGNATURE: String = {kotlin_string(literal('patch_webdialog_request_overload', 'sig'))}",
    f"    val WEBDIALOG_NEW_METHOD: String = {kotlin_string(literal('patch_webdialog_request_overload', 'new_method'))}",
    "}",
    "",
]

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes, {len(lines)} lines)")
