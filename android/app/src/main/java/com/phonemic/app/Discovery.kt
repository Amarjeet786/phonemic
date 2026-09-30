package com.phonemic.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** First contact over Wi-Fi: UDP broadcast "PMIC_DISCOVER" -> PCs answer "PMIC_HERE|name|port". */
object Discovery {
    const val DISCOVERY_PORT = 50506

    data class Pc(val name: String, val ip: String)

    /** Finds the Wi-Fi network even when it has no internet and Android prefers mobile data. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun isWifiConnected(ctx: Context): Boolean = wifiNetwork(ctx) != null

    /** Forces a socket to use Wi-Fi (falls back silently if no Wi-Fi network, e.g. phone is the hotspot). */
    fun bindToWifi(ctx: Context, socket: DatagramSocket) {
        try { wifiNetwork(ctx)?.bindSocket(socket) } catch (_: Exception) {}
    }

    /** Blocking; call from a background thread. */
    fun scan(ctx: Context, timeoutMs: Int = 2000): List<Pc> {
        val found = LinkedHashMap<String, Pc>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            bindToWifi(ctx, socket)
            socket.broadcast = true
            socket.soTimeout = 400
            val msg = "PMIC_DISCOVER".toByteArray()
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith("PMIC_HERE|")) {
                        val name = text.split("|").getOrElse(1) { "PC" }
                        val ip = p.address.hostAddress ?: continue
                        found[ip] = Pc(name, ip)
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        return found.values.toList()
    }
}
