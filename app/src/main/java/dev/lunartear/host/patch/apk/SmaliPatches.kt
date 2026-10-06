package dev.lunartear.host.patch.apk

import java.io.File

/** How one of the reference patcher's smali edits turned out. */
enum class SmaliEditOutcome {
    /** The edit changed the file. */
    APPLIED,

    /** The file already carries the edit (a second patch run). */
    ALREADY_APPLIED,

    /** The reference's marker was not found: a different SDK build. */
    NOT_FOUND,
}

/** One edit from `patch_apk.py`, with the detail it printed. */
data class SmaliEdit(
    val name: String,
    val outcome: SmaliEditOutcome,
    val detail: String = "",
)

/**
 * The reference patcher's Facebook-SDK smali surgery, as pure text transforms.
 *
 * `lunar-scripts/android/patch_apk.py` runs on an apktool-decoded tree and does
 * five things when it is given `--auth-host`:
 *
 *  1. [`patch_facebook_smali`] rewrites the SDK's domain and format strings in
 *     every com/facebook file in the smali roots;
 *  2. [`bypass_cct_redirect_check`] makes `CustomTabUtils.getValidRedirectURI()`
 *     return the default `fbconnect://cct.<pkg>` URI instead of `""` (the SDK
 *     returns `""` whenever another installed app also listens for `fbconnect:`,
 *     which is the case on any device with the Facebook app or Lite);
 *  3. [`force_webview_only_login_behavior`] flips
 *     `LoginBehavior.NATIVE_WITH_FALLBACK`'s Katana/CustomTab/Lite/Instagram
 *     booleans off, leaving only the in-app WebView path - Chrome drops
 *     app-launched `http://` navigations to private IPs (HTTPS-Upgrade and
 *     Private Network Access), while WebView is governed by the app's own
 *     cleartext setting and still loads them;
 *  4. [`patch_customtab_use_plain_view_intent`] rewrites `CustomTab.openCustomTab`
 *     to launch a plain `Intent.ACTION_VIEW` (the iOS patcher does the same);
 *  5. [`patch_webdialog_request_overload`] adds the API-24
 *     `shouldOverrideUrlLoading(WebView, WebResourceRequest)` overload to
 *     `WebDialog$DialogWebViewClient`, without which WebView 75+ never delivers
 *     the `fbconnect://` navigation and `LoginManager`'s callback never fires.
 *
 * Steps 3 and 5 are the ones that actually make an unmodified app able to log in
 * against a local auth server: the string rewrites alone leave the client sitting
 * on the title screen failing to connect.
 *
 * The needles and replacement texts come from [SmaliPatchData], which is
 * generated from the reference's own source - see tools/gen-smali-patches.py.
 * These functions are the same operations that script performs, expressed as
 * string edits so they can be unit-tested against it directly.
 */
object SmaliPatches {

    /** Files the reference locates with `_find_smali`, relative to a smali root. */
    const val CUSTOM_TAB_UTILS = "com/facebook/internal/CustomTabUtils.smali"
    const val CUSTOM_TAB = "com/facebook/internal/CustomTab.smali"
    const val LOGIN_BEHAVIOR = "com/facebook/login/LoginBehavior.smali"
    const val WEB_DIALOG_CLIENT = "com/facebook/internal/WebDialog\$DialogWebViewClient.smali"

    /** The four edits that are located by class rather than by string. */
    val STRUCTURAL_FILES: Map<String, String> = mapOf(
        CUSTOM_TAB_UTILS to "getValidRedirectURI",
        CUSTOM_TAB to "openCustomTab",
        LOGIN_BEHAVIOR to "LoginBehavior",
        WEB_DIALOG_CLIENT to "shouldOverrideUrlLoading",
    )

    /** Every literal the string pass looks for, without the surrounding quotes. */
    val SEARCH_VALUES: List<String> =
        (SmaliPatchData.STRING_REPLACEMENTS.map { it.first } + SmaliPatchData.BASE_DOMAIN.first)
            .map { it.trim('"') }

    /**
     * `patch_facebook_smali`'s replacement list, with the auth host substituted
     * for the `None` entries exactly as the reference does.
     */
    fun stringReplacements(authHost: String): List<Pair<String, String>> {
        val host = "\"$authHost\""
        return SmaliPatchData.STRING_REPLACEMENTS.map { (old, new) -> old to (new ?: host) } +
            (SmaliPatchData.BASE_DOMAIN.first to (SmaliPatchData.BASE_DOMAIN.second ?: host))
    }

    /**
     * `patch_facebook_smali`: a plain text replace over one file's smali.
     * Returns the new text and how many distinct replacements matched.
     */
    fun applyStringReplacements(
        text: String,
        replacements: List<Pair<String, String>>,
    ): Pair<String, Int> {
        var out = text
        var matched = 0
        for ((old, new) in replacements) {
            if (old !in out) continue
            out = out.replace(old, new)
            matched++
        }
        return out to matched
    }

    /**
     * `bypass_cct_redirect_check`.
     *
     * The reference inserts the early return at the `:cond_0` label, i.e. at the
     * top of the method's real body and after the crash-shield check.
     */
    fun forceDefaultRedirectUri(text: String): Pair<String, SmaliEditOutcome> {
        val needle = SmaliPatchData.CCT_REDIRECT_NEEDLE
        if (needle !in text) return text to SmaliEditOutcome.NOT_FOUND
        val at = text.indexOf(needle)
        return text.replaceRange(at, at + needle.length, SmaliPatchData.CCT_REDIRECT_REPLACEMENT) to
            SmaliEditOutcome.APPLIED
    }

    /** `force_webview_only_login_behavior`. */
    fun forceWebViewOnlyLoginBehavior(text: String): Pair<String, SmaliEditOutcome> {
        val needle = SmaliPatchData.LOGIN_BEHAVIOR_NEEDLE
        val start = text.indexOf(needle)
        if (start < 0) return text to SmaliEditOutcome.NOT_FOUND
        val end = text.indexOf(SmaliPatchData.LOGIN_BEHAVIOR_BLOCK_END, start)
        if (end < 0) return text to SmaliEditOutcome.NOT_FOUND

        var block = text.substring(start, end)
        val flipped = mutableListOf<String>()
        for ((old, new) in SmaliPatchData.LOGIN_BEHAVIOR_FLIPS) {
            val at = block.indexOf(old)
            if (at < 0) continue
            block = block.replaceRange(at, at + old.length, new)
            flipped += old.substringAfter("const/4 ").substringBefore(",")
        }
        if (flipped.isEmpty()) return text to SmaliEditOutcome.ALREADY_APPLIED
        return (text.substring(0, start) + block + text.substring(end)) to SmaliEditOutcome.APPLIED
    }

    /** `patch_customtab_use_plain_view_intent`. */
    fun plainViewOpenCustomTab(text: String): Pair<String, SmaliEditOutcome> {
        val needle = SmaliPatchData.OPEN_CUSTOM_TAB_NEEDLE
        val at = text.indexOf(needle)
        if (at < 0) return text to SmaliEditOutcome.NOT_FOUND
        return text.replaceRange(at, at + needle.length, SmaliPatchData.OPEN_CUSTOM_TAB_REPLACEMENT) to
            SmaliEditOutcome.APPLIED
    }

    /** `patch_webdialog_request_overload`: the reference appends the method. */
    fun addWebDialogOverload(text: String): Pair<String, SmaliEditOutcome> {
        if (SmaliPatchData.WEBDIALOG_SIGNATURE in text) return text to SmaliEditOutcome.ALREADY_APPLIED
        val base = if (text.endsWith("\n")) text else "$text\n"
        return base + SmaliPatchData.WEBDIALOG_NEW_METHOD to SmaliEditOutcome.APPLIED
    }

    /**
     * Applies every edit that belongs to [relativePath] and reports what happened.
     *
     * The string pass runs over every com/facebook file (like the reference);
     * the four structural edits only apply to their own class.
     */
    fun applyToFile(
        relativePath: String,
        text: String,
        authHost: String,
    ): Pair<String, List<SmaliEdit>> {
        var out = text
        val edits = mutableListOf<SmaliEdit>()

        val (rewritten, matched) = applyStringReplacements(out, stringReplacements(authHost))
        if (matched > 0) {
            out = rewritten
            edits += SmaliEdit("strings", SmaliEditOutcome.APPLIED, "$matched replacement(s)")
        }

        when (relativePath) {
            CUSTOM_TAB_UTILS -> {
                val (next, outcome) = forceDefaultRedirectUri(out)
                out = next
                edits += SmaliEdit(
                    "getValidRedirectURI",
                    outcome,
                    if (outcome == SmaliEditOutcome.NOT_FOUND) {
                        "marker not found (already patched?)"
                    } else {
                        "-> getDefaultRedirectURI()"
                    },
                )
            }

            CUSTOM_TAB -> {
                val (next, outcome) = plainViewOpenCustomTab(out)
                out = next
                edits += SmaliEdit(
                    "openCustomTab",
                    outcome,
                    if (outcome == SmaliEditOutcome.NOT_FOUND) {
                        "body marker not found (already patched?)"
                    } else {
                        "-> plain Intent.ACTION_VIEW"
                    },
                )
            }

            LOGIN_BEHAVIOR -> {
                val (next, outcome) = forceWebViewOnlyLoginBehavior(out)
                out = next
                edits += SmaliEdit(
                    "LoginBehavior",
                    outcome,
                    when (outcome) {
                        SmaliEditOutcome.APPLIED -> "NATIVE_WITH_FALLBACK -> WebView-only"
                        SmaliEditOutcome.ALREADY_APPLIED -> "already WebView-only"
                        SmaliEditOutcome.NOT_FOUND -> "NATIVE_WITH_FALLBACK block not found"
                    },
                )
            }

            WEB_DIALOG_CLIENT -> {
                val (next, outcome) = addWebDialogOverload(out)
                out = next
                edits += SmaliEdit(
                    "shouldOverrideUrlLoading",
                    outcome,
                    when (outcome) {
                        SmaliEditOutcome.APPLIED -> "added API-24 overload"
                        SmaliEditOutcome.ALREADY_APPLIED -> "already has API-24 overload"
                        SmaliEditOutcome.NOT_FOUND -> "not found"
                    },
                )
            }
        }

        return out to edits
    }

    /** The file name a DEX class type maps to inside a smali root. */
    fun smaliPathFor(classType: String): String =
        classType.removePrefix("L").removeSuffix(";") + ".smali"

    /** Reverse mapping, used to find the file baksmali wrote for a class. */
    fun classTypeFor(smaliRoot: File, file: File): String {
        val relative = file.relativeTo(smaliRoot).path.replace(File.separatorChar, '/')
        return "L" + relative.removeSuffix(".smali") + ";"
    }
}
