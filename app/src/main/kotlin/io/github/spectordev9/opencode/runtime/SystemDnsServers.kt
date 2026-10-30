package io.github.spectordev9.opencode.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.InetAddress

object SystemDnsServers {
    fun get(context: Context): List<String> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val lp = cm?.getLinkProperties(network)
            val servers = lp?.dnsServers?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotEmpty() && it != "127.0.0.1" }
                ?: emptyList()
            val cleaned = servers.map { s ->
                val clean = s.substringBefore('%') // drop scope
                if (clean.contains(':') && !clean.startsWith('[')) "[$clean]" else clean
            }.distinct().take(3)
            if (cleaned.isNotEmpty()) cleaned else listOf("1.1.1.1", "8.8.8.8")
        } catch (_: Throwable) {
            listOf("1.1.1.1", "8.8.8.8")
        }
    }
}
