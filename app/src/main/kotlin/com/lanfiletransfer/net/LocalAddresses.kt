package com.lanfiletransfer.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** この端末が持つ IPv4 アドレス(相手に伝えるために表示する)。 */
data class LocalAddress(val interfaceName: String, val ipv4: String, val isWireless: Boolean)

object LocalAddresses {

    fun list(): List<LocalAddress> {
        val result = mutableListOf<LocalAddress>()

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
        for (nif in interfaces) {
            if (!runCatching { nif.isUp }.getOrDefault(false) || runCatching { nif.isLoopback }.getOrDefault(true)) {
                continue
            }

            val name = nif.displayName ?: nif.name ?: continue
            val wireless = name.contains("wlan", ignoreCase = true) ||
                name.contains("wi-fi", ignoreCase = true) ||
                name.contains("wifi", ignoreCase = true)

            for (address in nif.inetAddresses) {
                if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) {
                    continue
                }
                val text = address.hostAddress ?: continue
                result.add(LocalAddress(name, text, wireless))
            }
        }

        // Wi-Fi を先に、次に名前順
        return result.sortedWith(compareByDescending<LocalAddress> { it.isWireless }.thenBy { it.interfaceName })
    }
}
