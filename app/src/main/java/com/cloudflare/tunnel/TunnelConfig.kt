package com.cloudflare.tunnel

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
enum class TunnelType : Parcelable {
    QUICK, NAMED
}

@Parcelize
data class TunnelConfig(
    var tunnelType: TunnelType = TunnelType.QUICK,
    var tunnelName: String = "",
    var tunnelId: String = "",
    var credentialsFile: String = "",
    var localPort: Int = 8080,
    var protocol: String = "http",
    var hostname: String = "",
    var useCustomHostname: Boolean = false,
    var enableHTTPS: Boolean = true,
    var logLevel: String = "info",
    var region: String = "auto",
    var enableMetrics: Boolean = false,
    var metricsPort: Int = 9090,

    // Firebase settings
    var firebaseDatabaseUrl: String = "",
    var firebaseApiKey: String = "",
    var enableFirebaseSync: Boolean = false
) : Parcelable {

    fun describe(): String =
        if (tunnelType == TunnelType.QUICK) {
            "${protocol}://localhost:${localPort} (quick tunnel)"
        } else {
            val idPart = if (tunnelId.isNotBlank()) " [ID: ${tunnelId.take(8)}...]" else ""
            "${protocol}://localhost:${localPort} -> $tunnelName$idPart ($hostname)"
        }

    fun hasFirebase(): Boolean =
        firebaseDatabaseUrl.isNotBlank()

    fun getFirebaseDisplay(): String =
        if (hasFirebase()) {
            "✓ Firebase: ${firebaseDatabaseUrl.substringAfter("https://").take(30)}..."
        } else {
            "○ No Firebase sync"
        }
}
