package dev.lunartear.host.core

import android.content.Context
import org.json.JSONObject

/** Where the servers advertise themselves. Same-device play uses loopback. */
enum class HostMode {
    /** 127.0.0.1 - the client runs on this device. */
    LOOPBACK,

    /** The device's Wi-Fi/LAN address - the client runs on another device. */
    LAN,

    /** Whatever the user typed. */
    CUSTOM,
}

/**
 * Everything the supervisor needs to launch the servers.
 *
 * `rememberedPatch*` records what the *installed* client was patched with. The
 * patched APK has the host and gRPC port baked into `libil2cpp.so` and
 * `global-metadata.dat`, so changing them here silently breaks the client -
 * the UI warns when the two disagree.
 */
data class ServerConfig(
    val assetRoot: String? = null,
    /** The client APK the user selected for patching (a path on shared storage). */
    val sourceApk: String? = null,
    /**
     * Port the bundled auth-server listens on. It impersonates Facebook's OAuth
     * endpoints, which is what the patched client's Facebook SDK is redirected to
     * (see [dev.lunartear.host.patch.apk.SmaliPatches]); without it the client
     * hangs ~100 s after login and never starts downloading assets.
     * 0 disables both the server and the redirect.
     */
    val authPort: Int = 3000,
    val hostMode: HostMode = HostMode.LOOPBACK,
    val customHost: String = "",
    val grpcPort: Int = 8003,
    val cdnPort: Int = 8080,
    val adminPort: Int = 8082,
    val autoRestart: Boolean = true,
    val rememberPatch: Boolean = true,
    val patchedHost: String? = null,
    val patchedGrpcPort: Int? = null,
    val patchedCdnPort: Int? = null,
) {
    /** Directory that must contain `assets/`; also the working directory of both servers. */
    val assetsDir: String? get() = assetRoot

    /** Layout the servers expect, relative to [assetRoot]. */
    val masterDataPath: String? get() = assetRoot?.let { "$it/assets/release/${AssetLayout.MASTER_DATA_NAME}" }

    /**
     * The `host:port` the client's Facebook SDK is rewritten to while patching.
     *
     * Plain `127.0.0.1`, matching the community's own configuration. It goes into
     * the SDK's smali, which is reassembled, so unlike the metadata strings it is
     * not length-constrained - and it never touches the Unity/C# half, whose graph
     * URL template cannot be redirected to a local port at all.
     */
    val patchAuthHost: String? get() = if (authPort in 1..65535) "127.0.0.1:$authPort" else null

    fun portsMatchRememberedPatch(): Boolean =
        patchedGrpcPort == null || (patchedGrpcPort == grpcPort && patchedCdnPort == cdnPort)

    fun hostMatchesRememberedPatch(resolvedHost: String): Boolean =
        patchedHost == null || patchedHost == resolvedHost

    companion object {
        val DEFAULT = ServerConfig()
    }
}

/** Small JSON-backed store; avoids a DataStore/Proto dependency for a handful of fields. */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("lunar-tear-host", Context.MODE_PRIVATE)

    fun load(): ServerConfig {
        val raw = prefs.getString(KEY, null) ?: return ServerConfig.DEFAULT
        return runCatching {
            val o = JSONObject(raw)
            ServerConfig(
                assetRoot = o.optStringOrNull("assetRoot"),
                sourceApk = o.optStringOrNull("sourceApk"),
                authPort = o.optInt("authPort", 3000),
                hostMode = runCatching { HostMode.valueOf(o.optString("hostMode", HostMode.LOOPBACK.name)) }
                    .getOrDefault(HostMode.LOOPBACK),
                customHost = o.optString("customHost", ""),
                grpcPort = o.optInt("grpcPort", 8003),
                cdnPort = o.optInt("cdnPort", 8080),
                adminPort = o.optInt("adminPort", 8082),
                autoRestart = o.optBoolean("autoRestart", true),
                rememberPatch = o.optBoolean("rememberPatch", true),
                patchedHost = o.optStringOrNull("patchedHost"),
                patchedGrpcPort = o.optIntOrNull("patchedGrpcPort"),
                patchedCdnPort = o.optIntOrNull("patchedCdnPort"),
            )
        }.getOrDefault(ServerConfig.DEFAULT)
    }

    fun save(config: ServerConfig) {
        val o = JSONObject().apply {
            put("assetRoot", config.assetRoot ?: JSONObject.NULL)
            put("sourceApk", config.sourceApk ?: JSONObject.NULL)
            put("authPort", config.authPort)
            put("hostMode", config.hostMode.name)
            put("customHost", config.customHost)
            put("grpcPort", config.grpcPort)
            put("cdnPort", config.cdnPort)
            put("adminPort", config.adminPort)
            put("autoRestart", config.autoRestart)
            put("rememberPatch", config.rememberPatch)
            put("patchedHost", config.patchedHost ?: JSONObject.NULL)
            put("patchedGrpcPort", config.patchedGrpcPort ?: JSONObject.NULL)
            put("patchedCdnPort", config.patchedCdnPort ?: JSONObject.NULL)
        }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private companion object {
        const val KEY = "config"
    }
}
