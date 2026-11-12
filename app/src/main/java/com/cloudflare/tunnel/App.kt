package com.cloudflare.tunnel

import android.app.Application

/** TunnelManager এখন অ্যাপ-স্কোপে একটাই — Activity মরে গেলেও টানেল ও নোটিফিকেশন ঠিক থাকে। */
class App : Application() {
    val tunnelManager: TunnelManager by lazy {
        TunnelManager(applicationContext).also { it.loadSavedInstances() }
    }
}
