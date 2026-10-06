package dev.lunartear.host.patch.apk

// GENERATED FILE - do not edit by hand.
//
// Produced by tools/gen-smali-patches.py from
// lunar-scripts/android/patch_apk.py: the needles and replacements below are
// evaluated out of the reference's own source with ast, so the port cannot
// drift from it by a retyped literal. Regenerate after changing the reference;
// SmaliPatchesTest fails when this file and the reference disagree.

/** The reference patcher's Facebook-SDK smali edits, verbatim. */
internal object SmaliPatchData {

    /** `SMALI_REPLACEMENTS`: a null replacement means the auth host. */
    val STRING_REPLACEMENTS: List<Pair<String, String?>> = listOf(
        "\"m.%s\"" to "\"%s\"",
        "\"https://graph.%s\"" to "\"http://%s\"",
        "\"https://graph-video.%s\"" to "\"http://%s\"",
        "\"https\"" to "\"http\"",
        "\"com.facebook.katana\"" to "\"com.disabled.katana\"",
        "\"com.facebook.orca\"" to "\"com.disabled.orca\"",
        "\"www.facebook.com\"" to null,
        "\"graph.facebook.com\"" to null,
    )

    /** `SMALI_BASE_DOMAIN`: the base domain, replaced separately. */
    val BASE_DOMAIN: Pair<String, String?> = "\"facebook.com\"" to null

    /** `bypass_cct_redirect_check` - getValidRedirectURI returns the default. */
    val CCT_REDIRECT_NEEDLE: String = """    :cond_0
    :try_start_0
    const-string v1, "developerDefinedRedirectURI"
"""
    val CCT_REDIRECT_REPLACEMENT: String = """    :cond_0
    invoke-static {}, Lcom/facebook/internal/CustomTabUtils;->getDefaultRedirectURI()Ljava/lang/String;

    move-result-object p0

    return-object p0

    :try_start_0
    const-string v1, "developerDefinedRedirectURI"
"""

    /** `patch_customtab_use_plain_view_intent` - whole openCustomTab body. */
    val OPEN_CUSTOM_TAB_NEEDLE: String = """.method public final openCustomTab(Landroid/app/Activity;Ljava/lang/String;)Z
    .locals 3

    invoke-static {p0}, Lcom/facebook/internal/instrument/crashshield/CrashShieldHandler;->isObjectCrashing(Ljava/lang/Object;)Z

    move-result v0

    const/4 v1, 0x0

    if-eqz v0, :cond_0

    return v1

    :cond_0
    :try_start_0
    const-string v0, "activity"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    .line 38
    sget-object v0, Lcom/facebook/login/CustomTabPrefetchHelper;->Companion:Lcom/facebook/login/CustomTabPrefetchHelper${'$'}Companion;

    invoke-virtual {v0}, Lcom/facebook/login/CustomTabPrefetchHelper${'$'}Companion;->getPreparedSessionOnce()Landroidx/browser/customtabs/CustomTabsSession;

    move-result-object v0

    .line 39
    new-instance v2, Landroidx/browser/customtabs/CustomTabsIntent${'$'}Builder;

    invoke-direct {v2, v0}, Landroidx/browser/customtabs/CustomTabsIntent${'$'}Builder;-><init>(Landroidx/browser/customtabs/CustomTabsSession;)V

    invoke-virtual {v2}, Landroidx/browser/customtabs/CustomTabsIntent${'$'}Builder;->build()Landroidx/browser/customtabs/CustomTabsIntent;

    move-result-object v0

    .line 40
    iget-object v2, v0, Landroidx/browser/customtabs/CustomTabsIntent;->intent:Landroid/content/Intent;

    invoke-virtual {v2, p2}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    .line 42
    :try_start_1
    check-cast p1, Landroid/content/Context;

    iget-object p2, p0, Lcom/facebook/internal/CustomTab;->uri:Landroid/net/Uri;

    invoke-virtual {v0, p1, p2}, Landroidx/browser/customtabs/CustomTabsIntent;->launchUrl(Landroid/content/Context;Landroid/net/Uri;)V
    :try_end_1
    .catch Landroid/content/ActivityNotFoundException; {:try_start_1 .. :try_end_1} :catch_0
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    const/4 p1, 0x1

    return p1

    :catch_0
    return v1

    :catchall_0
    move-exception p1

    .line 46
    invoke-static {p1, p0}, Lcom/facebook/internal/instrument/crashshield/CrashShieldHandler;->handleThrowable(Ljava/lang/Throwable;Ljava/lang/Object;)V

    return v1
.end method
"""
    val OPEN_CUSTOM_TAB_REPLACEMENT: String = """.method public final openCustomTab(Landroid/app/Activity;Ljava/lang/String;)Z
    .locals 4

    invoke-static {p0}, Lcom/facebook/internal/instrument/crashshield/CrashShieldHandler;->isObjectCrashing(Ljava/lang/Object;)Z

    move-result v0

    const/4 v1, 0x0

    if-eqz v0, :cond_0

    return v1

    :cond_0
    const-string v0, "activity"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    :try_start_0
    new-instance v2, Landroid/content/Intent;

    const-string v3, "android.intent.action.VIEW"

    iget-object v0, p0, Lcom/facebook/internal/CustomTab;->uri:Landroid/net/Uri;

    invoke-direct {v2, v3, v0}, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V

    invoke-virtual {v2, p2}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;

    invoke-virtual {p1, v2}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    :try_end_0
    .catch Landroid/content/ActivityNotFoundException; {:try_start_0 .. :try_end_0} :catch_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    const/4 p1, 0x1

    return p1

    :catch_0
    return v1

    :catchall_0
    move-exception p1

    invoke-static {p1, p0}, Lcom/facebook/internal/instrument/crashshield/CrashShieldHandler;->handleThrowable(Ljava/lang/Throwable;Ljava/lang/Object;)V

    return v1
.end method
"""

    /** `force_webview_only_login_behavior` - NATIVE_WITH_FALLBACK's booleans. */
    val LOGIN_BEHAVIOR_NEEDLE: String = "    const-string v1, \"NATIVE_WITH_FALLBACK\""
    val LOGIN_BEHAVIOR_FLIPS: List<Pair<String, String>> = listOf(
        "const/4 v4, 0x1" to "const/4 v4, 0x0",
        "const/4 v7, 0x1" to "const/4 v7, 0x0",
        "const/4 v8, 0x1" to "const/4 v8, 0x0",
        "const/4 v9, 0x1" to "const/4 v9, 0x0",
    )
    /** The reference ends the block at the next `invoke-direct/range`. */
    const val LOGIN_BEHAVIOR_BLOCK_END: String = "invoke-direct/range"

    /** `patch_webdialog_request_overload` - the added API-24 override. */
    val WEBDIALOG_SIGNATURE: String = "shouldOverrideUrlLoading(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;)Z"
    val WEBDIALOG_NEW_METHOD: String = """
.method public shouldOverrideUrlLoading(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;)Z
    .locals 1

    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;

    move-result-object v0

    invoke-virtual {v0}, Landroid/net/Uri;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p0, p1, v0}, Lcom/facebook/internal/WebDialog${'$'}DialogWebViewClient;->shouldOverrideUrlLoading(Landroid/webkit/WebView;Ljava/lang/String;)Z

    move-result v0

    return v0
.end method
"""
}
