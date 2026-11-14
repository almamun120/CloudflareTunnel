package com.cloudflare.tunnel

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class TunnelInstance(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Tunnel ${System.currentTimeMillis() % 1000}",
    val config: TunnelConfig,
    var isRunning: Boolean = false,
    var tunnelUrl: String = "",
    var isConnected: Boolean = false,   // URL পাওয়া / named টানেল কানেকশন রেজিস্টার হয়েছে
    var process: Int = -1, // Process ID for tracking
    val logs: MutableList<LogEntry> = mutableListOf()
) : Parcelable {
    fun describe(): String = "$name - ${config.describe()}"
}
