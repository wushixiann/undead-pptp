// SPDX-License-Identifier: GPL-3.0-or-later
package me.jinsei.pptp.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities

object NetworkUtil {

    data class Candidate(
        val ifaceName: String,
        val transport: String, // "wifi" / "ethernet" / "cellular" / "other"
        val validated: Boolean,
    )

    /**
     * Picks the underlay interface for the helper's raw GRE socket.
     *
     *  1. If [ConnectivityManager.getActiveNetwork] returns a non-VPN network
     *     with INTERNET capability, use its iface name. This respects Android's
     *     own routing decision and matches what apps (and Windows on the same
     *     LAN) would naturally do.
     *  2. Otherwise, fall back to iterating all networks in priority order
     *     WiFi → Ethernet → Cellular. Skipping VPN and uninstalled networks.
     *
     * The earlier version iterated `allNetworks` in undefined order, so on a
     * device with both WiFi and cellular up it could pick `rmnet1`. Bind to a
     * cellular iface and PPTP GRE replies get black-holed by CGNAT.
     */
    fun activeUnderlayInterface(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                cm.getLinkProperties(active)?.interfaceName?.let { return it }
            }
        }

        val priorityOrder = listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_CELLULAR,
        )
        for (transport in priorityOrder) {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (!caps.hasTransport(transport)) continue
                cm.getLinkProperties(n)?.interfaceName?.let { return it }
            }
        }
        return null
    }

    /**
     * Enumerate all currently-usable underlays. Used by the UI to let the user
     * manually pick when the auto-selection misfires.
     */
    fun listCandidates(context: Context): List<Candidate> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = mutableListOf<Candidate>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val link = cm.getLinkProperties(n) ?: continue
            val name = link.interfaceName ?: continue
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            out.add(Candidate(name, transport, validated))
        }
        return out
    }
}
