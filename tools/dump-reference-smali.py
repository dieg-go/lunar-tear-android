"""Dump the reference patcher's smali needles/replacements verbatim.

The on-device patcher (Kotlin) has to apply the same text edits as
lunar-scripts/android/patch_apk.py. Transcribing them by hand is how a port
silently diverges, so this script imports the reference and writes each
needle/replacement pair to tools/.cache/smali-patches/ for inspection and for
the equivalence test to compare against.

    python tools/dump-reference-smali.py [repo-root] [out-dir]
"""

import importlib.util
import json
import os
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\diego\lunar-scripts"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "tools", ".cache", "smali-patches",
)

spec = importlib.util.spec_from_file_location(
    "patch_apk", os.path.join(REPO, "android", "patch_apk.py")
)
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)

os.makedirs(OUT, exist_ok=True)

# (name, value) pairs that the Kotlin port must reproduce exactly.
strings = {
    "SMALI_REPLACEMENTS": ref.SMALI_REPLACEMENTS,
    "SMALI_BASE_DOMAIN": ref.SMALI_BASE_DOMAIN,
}

# Function bodies hold the structural edits; pull the literals out of the source
# with ast so nothing is retyped.
import ast
import inspect

src = open(os.path.join(REPO, "android", "patch_apk.py"), encoding="utf-8").read()
tree = ast.parse(src)


def fold(node):
    """Evaluate a Str/Num/List/Tuple/BinOp-Add/Name(None) expression."""
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


functions = {}
for node in tree.body:
    if isinstance(node, ast.FunctionDef):
        functions[node.name] = node

for fname in (
    "bypass_cct_redirect_check",
    "patch_customtab_use_plain_view_intent",
    "force_webview_only_login_behavior",
    "patch_webdialog_request_overload",
):
    fn = functions[fname]
    for stmt in ast.walk(fn):
        if isinstance(stmt, ast.Assign) and len(stmt.targets) == 1:
            target = stmt.targets[0]
            if isinstance(target, ast.Name) and target.id in ("needle", "replacement", "new_method", "flips", "sig"):
                strings[f"{fname}.{target.id}"] = fold(stmt.value)

with open(os.path.join(OUT, "patches.json"), "w", encoding="utf-8") as f:
    json.dump(strings, f, indent=1, ensure_ascii=False)

# Also drop the raw text of each string so it can be pasted into Kotlin safely.
for key, value in strings.items():
    safe = key.replace(".", "_")
    with open(os.path.join(OUT, safe + ".txt"), "w", encoding="utf-8", newline="") as f:
        if isinstance(value, str):
            f.write(value)
        else:
            f.write(json.dumps(value, indent=1, ensure_ascii=False))

print(f"wrote {len(strings)} entries to {OUT}")
for key, value in strings.items():
    n = len(value) if isinstance(value, str) else len(value)
    print(f"  {key}: {n}")
