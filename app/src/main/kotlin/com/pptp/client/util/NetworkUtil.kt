package com.pptp.client.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtil {

    /**
     * Returns the interface name of the currently active non-VPN network
     * (e.g. "wlan0", "rmnet_data0"). Null if no active network or LinkProperties
     * lacks an interface name.
     */
    fun activeUnderlayInterface(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = cm.allNetworks
        for (n in candidates) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val link = cm.getLinkProperties(n) ?: continue
            link.interfaceName?.let { return it }
        }
        return null
    }
}
