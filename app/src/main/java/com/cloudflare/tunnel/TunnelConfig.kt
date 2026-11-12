package com.cloudflare.tunnel

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TunnelConfig(
    var localPort: Int = 8080,
    var protocol: String = "http",
    var hostname: String = "",
    var useCustomHostname: Boolean = false,
    var enableHTTPS: Boolean = true,
    var logLevel: String = "info",
    var region: String = "auto",
    var enableMetrics: Boolean = false,
    var metricsPort: Int = 9090
) : Parcelable {
    fun describe(): String =
        "${protocol}://localhost:${localPort}" +
                if (useCustomHostname && hostname.isNotEmpty()) " -> $hostname" else " (quick tunnel)"
}