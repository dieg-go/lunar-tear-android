package dev.lunartear.host.patch.apk

/**
 * The addresses a patched client will use, and how they map onto the client's own strings.
 */
data class PatchTarget(
    val host: String,
    val grpcPort: Int,
    val cdnPort: Int,
    /**
     * `host:port` of the local auth-server that impersonates Facebook's OAuth
     * endpoints (e.g. `127.0.0.1:3000`), or null to leave the Facebook SDK alone.
     *
     * Without it the client authenticates, fetches the catalog and the master
     * data, and then hangs ~100 s on a Facebook SDK call and shows
     * "Failed to connect. Retrying." - it never starts downloading assets.
     *
     * The value is written into the Facebook SDK's *smali* (see [SmaliPatches]),
     * which smali reassembles, so its length is not constrained by the string it
     * replaces.
     */
    val authHost: String? = null,
) {
    val cdnBaseUrl: String get() = "http://$host:$cdnPort"

    fun describe(): String =
        "gRPC $host:$grpcPort, CDN $cdnBaseUrl" + if (grpcPort == Il2CppPatcher.DEFAULT_GRPC_PORT) {
            " (no port override needed)"
        } else {
            " (3 libil2cpp.so port patches)"
        } + (authHost?.let { " + Facebook SDK -> $it (smali)" } ?: "")
}

/**
 * The string rewrites the client needs in order to talk to a local server.
 *
 * Mirrors the replacement list in patch_apk.py's main(), including the detail
 * that the gRPC host must be replaced by a **bare host** with no port: the client
 * appends `_serverPort` itself when it opens the channel, so a port here would
 * produce `host:port:port`.
 *
 * The Facebook half is *not* here. `global-metadata.dat` is the Unity/C# side of
 * the SDK, and the reference leaves it alone: the C# graph URL is built from
 * `Constants.GraphUrlFormat` (`https://graph.{0}/{1}/Unity.{0}`), a literal
 * embedded inside a longer one, so rewriting only the `{0}` domain produces
 * `https://graph.<auth host>/...` - a host that can never resolve, since no local
 * server can answer on `graph.` over 443. That breaks the login instead of
 * redirecting it, which is why the reference only rewrites the metadata domain
 * when the auth host fits in the 12 bytes of `facebook.com` *and* why it prints a
 * warning when the usual `127.0.0.1:3000` does not. The redirect belongs in the
 * Java SDK's own constants, and that is what [SmaliPatches] does.
 */
object PatchRecipe {

    const val GRPC_HOST_ORIGINAL = "api.app.nierreincarnation.com"
    const val WEB_BASE_ORIGINAL = "https://web.app.nierreincarnation.com"
    const val DATABASE_ORIGINAL = "https://web.app.nierreincarnation.com/assets/release/{0}/database.bin"
    const val RESOURCES_ORIGINAL = "https://resources-api.app.nierreincarnation.com/"

    /**
     * The metadata rewrites, in the reference's order.
     *
     * The C# half of the Facebook SDK is deliberately absent - see the class
     * comment.
     */
    fun replacements(target: PatchTarget): List<Replacement> = listOf(
        Replacement(GRPC_HOST_ORIGINAL, target.host),
        Replacement(DATABASE_ORIGINAL, "${target.cdnBaseUrl}/assets/release/{0}/database.bin"),
        Replacement(WEB_BASE_ORIGINAL, target.cdnBaseUrl),
        Replacement(RESOURCES_ORIGINAL, "${target.cdnBaseUrl}/"),
    )

    /**
     * Checks every replacement fits inside the string it replaces.
     *
     * The reference exits here; we surface the same information as a list of
     * problems so the UI can show it before the user starts a patch that would
     * fail halfway.
     */
    fun problems(target: PatchTarget): List<String> {
        val problems = mutableListOf<String>()
        if (target.grpcPort !in 1..65535) problems += "gRPC port ${target.grpcPort} is out of range"
        if (target.cdnPort !in 1..65535) problems += "CDN port ${target.cdnPort} is out of range"
        if (target.host.isBlank()) problems += "host is empty"
        if (target.host.contains(':')) problems += "host must not contain a port (the client appends it)"
        replacements(target).forEach { replacement ->
            if (replacement.required && !replacement.fits) {
                problems += "does not fit: ${replacement.old} -> ${replacement.new} " +
                    "(${replacement.newBytes.size} > ${replacement.oldBytes.size} bytes); " +
                    "use a shorter host, or the default port"
            }
        }
        target.authHost?.let { auth ->
            if (auth.substringAfterLast(':', "").toIntOrNull() == null) {
                problems += "auth host must include a port (e.g. 127.0.0.1:3000)"
            }
        }
        return problems
    }
}
