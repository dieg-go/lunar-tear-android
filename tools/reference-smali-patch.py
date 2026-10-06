"""Apply the reference patcher's Facebook smali edits to a disassembled tree.

    python tools/reference-smali-patch.py <lunar-scripts-root> <tree> <auth-host>

`<tree>` must contain a `smali/` directory, i.e. the output of baksmali. The five
calls below are exactly what lunar-scripts/android/patch_apk.py does in its
`--auth-host` mode, in the same order, so the Kotlin port can be diffed against
the real thing - see SmaliPatchesTest.
"""

import importlib.util
import os
import sys

repo, tree, auth = sys.argv[1], sys.argv[2], sys.argv[3]

spec = importlib.util.spec_from_file_location(
    "patch_apk", os.path.join(repo, "android", "patch_apk.py")
)
ref = importlib.util.module_from_spec(spec)
sys.modules["patch_apk"] = ref
spec.loader.exec_module(ref)

ref.patch_facebook_smali(tree, auth)
ref.bypass_cct_redirect_check(tree)
ref.force_webview_only_login_behavior(tree)
ref.patch_customtab_use_plain_view_intent(tree)
ref.patch_webdialog_request_overload(tree)

print("reference smali edits applied")
