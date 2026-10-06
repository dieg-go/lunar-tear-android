package dev.lunartear.host.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.InetSocketAddress

/** LAN address discovery, used to tell the user which address to bake into the client. */
object NetInfo {

    /** Site-local IPv4 addresses, Wi-Fi first. */
    fun lanAddresses(): List<String> {
        val wifi = mutableListOf<String>()
        val other = mutableListOf<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("169.254.")) continue
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                        wifi += host
                    } else {
                        other += host
                    }
                }
            }
        }
        return (wifi.distinct() + other.distinct())
    }

    fun preferredLanAddress(): String? = lanAddresses().firstOrNull()

    fun tcpReachable(host: String, port: Int, timeoutMs: Int = 600): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /** True when something is already listening, i.e. the port would clash. */
    fun portInUse(port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("127.0.0.1", port))
            false
        }
    }.getOrDefault(true)
}
