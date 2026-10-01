package com.phonemic.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * First contact: UDP broadcast "PMIC_DISCOVER" on every active network interface
 * (Wi-Fi, USB tethering, Bluetooth tethering). PCs answer "PMIC_HERE|name|port".
 */
object Discovery {
    const val DISCOVERY_PORT = 50506

    data class Pc(val name: String, val ip: String, val via: String)

    /** Finds the Wi-Fi network even when it has no internet and Android prefers mobile data. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun isWifiConnected(ctx: Context): Boolean = wifiNetwork(ctx) != null

    /** Forces a socket onto Wi-Fi (only used in Wi-Fi mode). */
    fun bindToWifi(ctx: Context, socket: DatagramSocket) {
        try { wifiNetwork(ctx)?.bindSocket(socket) } catch (_: Exception) {}
    }

    private fun kindOf(ifName: String): String {
        val n = ifName.lowercase()
        return when {
            n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("wifi") -> "Wi-Fi"
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") || n.startsWith("eth") -> "USB cable"
            n.startsWith("bt") || n.startsWith("bnep") -> "Bluetooth"
            else -> "Network"
        }
    }

    /** Blocking; call from a background thread. */
    fun scan(timeoutMs: Int = 2500): List<Pc> {
        val sockets = mutableListOf<Pair<DatagramSocket, String>>()
        val found = LinkedHashMap<String, Pc>()
        try {
            val msg = "PMIC_DISCOVER".toByteArray()
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val local = ia.address as? Inet4Address ?: continue
                        val bc = ia.broadcast ?: continue
                        try {
                            val s = DatagramSocket(InetSocketAddress(local, 0))
                            s.broadcast = true
                            s.soTimeout = 100
                            s.send(DatagramPacket(msg, msg.size, bc, DISCOVERY_PORT))
                            sockets += s to kindOf(ni.name)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                for ((s, via) in sockets) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length)
                        if (text.startsWith("PMIC_HERE|")) {
                            val name = text.split("|").getOrElse(1) { "PC" }
                            val ip = p.address.hostAddress ?: continue
                            found[ip] = Pc(name, ip, via)
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            sockets.forEach { try { it.first.close() } catch (_: Exception) {} }
        }
        // Wi-Fi results first.
        return found.values.sortedBy { if (it.via == "Wi-Fi") 0 else 1 }
    }
}
