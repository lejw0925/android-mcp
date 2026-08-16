package dev.androidmcp.util

import java.net.NetworkInterface

object NetUtils {
    /** 本机局域网 IPv4 地址列表（排除回环）。 */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
            .sorted()
    }.getOrDefault(emptyList())
}
