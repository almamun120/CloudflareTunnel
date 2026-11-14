package com.cloudflare.tunnel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TunnelManager(private val context: Context) {

    companion object {
        private const val TAG = "TunnelManager"
        private const val BINARY_NAME = "cloudflared"
        private const val PREFS_TUNNELS = "tunnels"
        private const val PREFS_RUNNING_STATE = "running_state"

        init {
            System.loadLibrary("tunnel_manager")
        }
    }

    external fun nativeGetVersion(): String
    external fun nativeSetPermissions(filePath: String): Boolean

    private val binaryDownloader = BinaryDownloader(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Store multiple tunnel instances
    private val tunnelInstances = ConcurrentHashMap<String, TunnelInstanceRuntime>()

    // Firebase services for each tunnel
    private val firebaseServices = ConcurrentHashMap<String, FirebaseService>()

    // Persistence
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_TUNNELS, Context.MODE_PRIVATE)
    }

    private val runningStatePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_RUNNING_STATE, Context.MODE_PRIVATE)
    }

    data class TunnelInstanceRuntime(
        val instance: TunnelInstance,
        var process: Process? = null,
        var logJob: Job? = null,
        var monitorJob: Job? = null,
        var pid: Long = -1L
    )

    private var globalLogCallback: ((String) -> Unit)? = null
    private var instanceLogCallback: ((String, LogEntry) -> Unit)? = null
    private var tunnelUrlCallback: ((String, String) -> Unit)? = null
    private var statusCallback: ((String, Boolean) -> Unit)? = null

    fun setGlobalLogCallback(cb: (String) -> Unit) { globalLogCallback = cb }
    fun setInstanceLogCallback(cb: (String, LogEntry) -> Unit) { instanceLogCallback = cb }
    fun setTunnelUrlCallback(cb: (String, String) -> Unit) { tunnelUrlCallback = cb }
    fun setStatusCallback(cb: (String, Boolean) -> Unit) { statusCallback = cb }

    fun getBinaryPath() = "${context.filesDir.absolutePath}/$BINARY_NAME"
    fun getWrapperPath(instanceId: String) = "${context.filesDir.absolutePath}/run_tunnel_$instanceId.sh"

    fun isBinaryInstalled(): Boolean {
        val f = File(getBinaryPath())
        return f.exists() && f.length() > 1_000_000L
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ═══════════════════════════════════════════════════
    //  FIREBASE INTEGRATION
    // ═══════════════════════════════════════════════════

    private fun getFirebaseService(instance: TunnelInstance): FirebaseService? {
        if (!instance.config.hasFirebase() || !instance.config.enableFirebaseSync) {
            return null
        }

        return firebaseServices.getOrPut(instance.id) {
            FirebaseService(
                instance.config.firebaseDatabaseUrl,
                instance.config.firebaseApiKey
            ).apply {
                setLogCallback { msg -> log(instance.id, msg) }
            }
        }
    }

    private fun syncToFirebase(instance: TunnelInstance, status: String, url: String = "") {
        val firebase = getFirebaseService(instance) ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val data = TunnelFirebaseData(
                    url = url.ifBlank { instance.tunnelUrl },
                    name = instance.name,
                    localPort = instance.config.localPort,
                    protocol = instance.config.protocol,
                    status = status,
                    createdAt = getCurrentTimestamp(),
                    updatedAt = getCurrentTimestamp(),
                    isHealthy = instance.isRunning
                )

                firebase.saveTunnel(instance.id, data)

                if (status == "running" && url.isNotBlank()) {
                    firebase.addToHistory(data)
                }

            } catch (e: Exception) {
                log(instance.id, "[FIREBASE] Sync error: ${e.message}")
            }
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    // ═══════════════════════════════════════════════════
    //  PERSISTENCE - ENHANCED WITH RUNNING STATE
    // ═══════════════════════════════════════════════════

    fun loadSavedInstances() {
        // Load tunnel configurations
        val json = prefs.getString("instances", "[]") ?: "[]"
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val instance = parseInstance(obj)
                tunnelInstances[instance.id] = TunnelInstanceRuntime(instance)
            }
            Log.d(TAG, "Loaded ${tunnelInstances.size} saved tunnel(s)")

            // Load running state
            loadRunningState()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load instances", e)
        }
    }

    private fun parseInstance(obj: JSONObject): TunnelInstance = TunnelInstance(
        id = obj.getString("id"),
        name = obj.getString("name"),
        config = TunnelConfig(
            tunnelType = try { TunnelType.valueOf(obj.optString("tunnelType", "QUICK")) } catch (e: Exception) { TunnelType.QUICK },
            tunnelName = obj.optString("tunnelName", ""),
            tunnelId = obj.optString("tunnelId", ""),
            credentialsFile = obj.optString("credentialsFile", ""),
            localPort = obj.optInt("port", 8080),
            protocol = obj.optString("protocol", "http"),
            hostname = obj.optString("hostname", ""),
            useCustomHostname = obj.optBoolean("useCustomHostname", false),
            enableHTTPS = obj.optBoolean("enableHTTPS", true),
            logLevel = obj.optString("logLevel", "info"),
            region = obj.optString("region", "auto"),
            enableMetrics = obj.optBoolean("enableMetrics", false),
            metricsPort = obj.optInt("metricsPort", 9090),
            firebaseDatabaseUrl = obj.optString("firebaseDatabaseUrl", ""),
            firebaseApiKey = obj.optString("firebaseApiKey", ""),
            enableFirebaseSync = obj.optBoolean("enableFirebaseSync", false)
        )
    )

    /** সব টানেল কনফিগ JSON হিসেবে (ব্যাকআপের জন্য). includeSecrets=false হলে Firebase API key বাদ যায়। */
    fun exportConfigJson(includeSecrets: Boolean): String {
        val src = JSONArray(prefs.getString("instances", "[]") ?: "[]")
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val o = JSONObject(src.getJSONObject(i).toString())
            if (!includeSecrets) o.put("firebaseApiKey", "")
            out.put(o)
        }
        return JSONObject().put("version", 1).put("tunnels", out).toString(2)
    }

    /** ব্যাকআপ থেকে টানেল কনফিগ ফেরত আনে (একই id থাকলে আপডেট; চালু টানেল বাদ)। @return কয়টা ইমপোর্ট হলো */
    fun importConfigJson(json: String): Int {
        val root = JSONObject(json)
        val arr = root.optJSONArray("tunnels") ?: return 0
        var n = 0
        for (i in 0 until arr.length()) {
            try {
                val inst = parseInstance(arr.getJSONObject(i))
                val existing = tunnelInstances[inst.id]
                if (existing != null) {
                    if (existing.instance.isRunning) continue
                    val c = existing.instance.config
                    existing.instance.name = inst.name
                    c.tunnelType = inst.config.tunnelType; c.tunnelName = inst.config.tunnelName
                    c.tunnelId = inst.config.tunnelId; c.credentialsFile = inst.config.credentialsFile
                    c.localPort = inst.config.localPort; c.protocol = inst.config.protocol
                    c.hostname = inst.config.hostname
                    c.firebaseDatabaseUrl = inst.config.firebaseDatabaseUrl
                    if (inst.config.firebaseApiKey.isNotBlank()) c.firebaseApiKey = inst.config.firebaseApiKey
                    c.enableFirebaseSync = inst.config.enableFirebaseSync
                } else {
                    tunnelInstances[inst.id] = TunnelInstanceRuntime(inst)
                }
                n++
            } catch (e: Exception) {
                Log.w(TAG, "skip bad tunnel entry: ${e.message}")
            }
        }
        firebaseServices.clear()
        saveInstances()
        return n
    }

    private fun loadRunningState() {
        try {
            val runningJson = runningStatePrefs.getString("running_tunnels", "[]") ?: "[]"
            val runningArray = JSONArray(runningJson)

            var recoveredCount = 0

            for (i in 0 until runningArray.length()) {
                val obj = runningArray.getJSONObject(i)
                val instanceId = obj.getString("instance_id")
                val tunnelUrl = obj.optString("tunnel_url", "")
                val pid = obj.optLong("pid", -1L)

                val runtime = tunnelInstances[instanceId]
                if (runtime != null) {
                    // Check if process is still alive
                    if (pid > 0 && isProcessAlive(pid)) {
                        log(instanceId, "[INFO] Recovering running tunnel (PID: $pid)")

                        runtime.instance.isRunning = true
                        runtime.instance.tunnelUrl = tunnelUrl
                        runtime.pid = pid

                        // Try to reconnect to the process
                        tryReconnectToProcess(runtime, pid)

                        recoveredCount++
                    } else {
                        log(instanceId, "[WARN] Tunnel was running but process died")
                        runtime.instance.isRunning = false
                        runtime.instance.tunnelUrl = ""

                        // Update Firebase
                        syncToFirebase(runtime.instance, "stopped")
                    }
                }
            }

            if (recoveredCount > 0) {
                log("SYSTEM", "[SUCCESS] Recovered $recoveredCount running tunnel(s)")

                // Update foreground service
                scope.launch(Dispatchers.Main) {
                    updateForegroundService()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load running state", e)
        }
    }

    private fun saveRunningState() {
        try {
            val array = JSONArray()

            tunnelInstances.values.forEach { runtime ->
                if (runtime.instance.isRunning) {
                    val obj = JSONObject().apply {
                        put("instance_id", runtime.instance.id)
                        put("tunnel_url", runtime.instance.tunnelUrl)
                        put("pid", runtime.pid)
                    }
                    array.put(obj)
                }
            }

            runningStatePrefs.edit()
                .putString("running_tunnels", array.toString())
                .apply()

            Log.d(TAG, "Saved running state for ${array.length()} tunnel(s)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save running state", e)
        }
    }

    private fun saveInstances() {
        try {
            val array = JSONArray()
            tunnelInstances.values.forEach { runtime ->
                val obj = JSONObject().apply {
                    put("id", runtime.instance.id)
                    put("name", runtime.instance.name)
                    put("tunnelType", runtime.instance.config.tunnelType.name)
                    put("tunnelName", runtime.instance.config.tunnelName)
                    put("tunnelId", runtime.instance.config.tunnelId)
                    put("credentialsFile", runtime.instance.config.credentialsFile)
                    put("port", runtime.instance.config.localPort)
                    put("protocol", runtime.instance.config.protocol)
                    put("hostname", runtime.instance.config.hostname)
                    put("useCustomHostname", runtime.instance.config.useCustomHostname)
                    put("enableHTTPS", runtime.instance.config.enableHTTPS)
                    put("logLevel", runtime.instance.config.logLevel)
                    put("region", runtime.instance.config.region)
                    put("enableMetrics", runtime.instance.config.enableMetrics)
                    put("metricsPort", runtime.instance.config.metricsPort)
                    put("firebaseDatabaseUrl", runtime.instance.config.firebaseDatabaseUrl)
                    put("firebaseApiKey", runtime.instance.config.firebaseApiKey)
                    put("enableFirebaseSync", runtime.instance.config.enableFirebaseSync)
                }
                array.put(obj)
            }
            prefs.edit().putString("instances", array.toString()).apply()
            Log.d(TAG, "Saved ${tunnelInstances.size} tunnel(s)")

            // Also save running state
            saveRunningState()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save instances", e)
        }
    }

    // ═══════════════════════════════════════════════════
    //  PROCESS RECOVERY
    // ═══════════════════════════════════════════════════

    private fun isProcessAlive(pid: Long): Boolean {
        if (pid <= 0) return false

        return try {
            // Check if process exists in /proc
            val procDir = File("/proc/$pid")
            if (!procDir.exists()) {
                false
            } else {
                // Double check by reading cmdline
                val cmdline = File(procDir, "cmdline").readText()
                cmdline.contains("cloudflared")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun tryReconnectToProcess(runtime: TunnelInstanceRuntime, pid: Long) {
        try {
            log(runtime.instance.id, "[INFO] Monitoring recovered process")

            // Start a monitor job to check if process is still alive
            runtime.monitorJob = scope.launch(Dispatchers.IO) {
                while (isActive && runtime.instance.isRunning) {
                    delay(5000) // Check every 5 seconds

                    if (!isProcessAlive(pid)) {
                        log(runtime.instance.id, "[WARN] Recovered process died")
                        withContext(Dispatchers.Main) {
                            stopTunnel(runtime.instance.id, false)
                        }
                        break
                    }
                }
            }

            // Notify UI
            scope.launch(Dispatchers.Main) {
                statusCallback?.invoke(runtime.instance.id, true)
                if (runtime.instance.tunnelUrl.isNotBlank()) {
                    tunnelUrlCallback?.invoke(runtime.instance.id, runtime.instance.tunnelUrl)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect to process", e)
        }
    }

    // ═══════════════════════════════════════════════════
    //  MULTIPLE TUNNEL INSTANCE MANAGEMENT
    // ═══════════════════════════════════════════════════

    fun addInstance(instance: TunnelInstance) {
        tunnelInstances[instance.id] = TunnelInstanceRuntime(instance)
        saveInstances()
        log(instance.id, "[INFO] Added tunnel: ${instance.name}")

        // Test Firebase connection if enabled
        if (instance.config.hasFirebase() && instance.config.enableFirebaseSync) {
            scope.launch(Dispatchers.IO) {
                val firebase = getFirebaseService(instance)
                firebase?.testConnection()
            }
        }
    }

    fun updateInstance(instance: TunnelInstance) {
        val runtime = tunnelInstances[instance.id]
        if (runtime != null) {
            val existing = runtime.instance
            existing.name = instance.name
            existing.config.tunnelType = instance.config.tunnelType
            existing.config.tunnelName = instance.config.tunnelName
            existing.config.tunnelId = instance.config.tunnelId
            existing.config.hostname = instance.config.hostname
            existing.config.credentialsFile = instance.config.credentialsFile
            existing.config.localPort = instance.config.localPort
            existing.config.protocol = instance.config.protocol
            existing.config.firebaseDatabaseUrl = instance.config.firebaseDatabaseUrl
            existing.config.firebaseApiKey = instance.config.firebaseApiKey
            existing.config.enableFirebaseSync = instance.config.enableFirebaseSync
            
            Log.d(TAG, "Updated instance ${instance.id} to port ${instance.config.localPort}")
            saveInstances()

            // Clear old Firebase service and create new one
            firebaseServices.remove(instance.id)

            // Test new Firebase connection if enabled
            if (instance.config.hasFirebase() && instance.config.enableFirebaseSync) {
                scope.launch(Dispatchers.IO) {
                    val firebase = getFirebaseService(instance)
                    firebase?.testConnection()
                }
            }
        }
    }

    fun removeTunnel(instanceId: String) {
        val instance = tunnelInstances[instanceId]?.instance

        stopTunnel(instanceId)

        // Remove from Firebase if configured
        if (instance != null) {
            val firebase = getFirebaseService(instance)
            scope.launch(Dispatchers.IO) {
                firebase?.deleteTunnel(instanceId)
            }
        }

        tunnelInstances.remove(instanceId)
        firebaseServices.remove(instanceId)
        saveInstances()
    }

    fun getAllInstances(): List<TunnelInstance> =
        tunnelInstances.values.map { it.instance }.sortedBy { it.name }

    fun getInstance(instanceId: String): TunnelInstance? =
        tunnelInstances[instanceId]?.instance

    fun getRunningInstances(): List<TunnelInstance> =
        tunnelInstances.values.filter { it.instance.isRunning }.map { it.instance }

    fun isAnyTunnelRunning(): Boolean =
        tunnelInstances.values.any { it.instance.isRunning }

    // ═══════════════════════════════════════════════════
    //  START TUNNEL INSTANCE
    // ═══════════════════════════════════════════════════

    fun startTunnel(instance: TunnelInstance): Boolean {
        if (tunnelInstances[instance.id]?.instance?.isRunning == true) {
            log(instance.id, "[WARN] Tunnel already running")
            return false
        }

        if (!isBinaryInstalled()) {
            log(instance.id, "[ERROR] Binary not installed")
            return false
        }

        instance.tunnelUrl = if (instance.config.tunnelType == TunnelType.NAMED && instance.config.hostname.isNotBlank()) {
            val h = instance.config.hostname.trim()
            if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
        } else {
            ""
        }
        instance.isRunning = false
        instance.isConnected = false

        log(instance.id, "[INFO] Starting tunnel: ${instance.name}")
        log(instance.id, "[INFO] Config: ${instance.config.describe()}")

        if (instance.config.hasFirebase() && instance.config.enableFirebaseSync) {
            log(instance.id, "[INFO] Firebase sync enabled")
        }

        // Sync starting status to Firebase
        syncToFirebase(instance, "starting")

        val prefixDir = setupTermuxEnvironment()
        val wrapper = createWrapperScript(instance, prefixDir)

        val cmd = listOf("/system/bin/sh", wrapper.absolutePath)

        return try {
            val pb = ProcessBuilder(cmd).apply {
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = File(prefixDir, "tmp").absolutePath
                environment()["PREFIX"] = prefixDir.absolutePath
                redirectErrorStream(true)
            }

            val process = pb.start()

            // Wait and verify process started
            Thread.sleep(1000)
            try {
                val exitCode = process.exitValue()
                val output = process.inputStream.bufferedReader().readText()
                log(instance.id, "[ERROR] Process exited with code $exitCode")
                output.lines().filter { it.isNotBlank() }.take(20).forEach {
                    log(instance.id, "[ERROR] $it")
                }

                syncToFirebase(instance, "failed")
                return false
            } catch (e: IllegalThreadStateException) {
                // Process is still running - good!
                val pid = getPid(process)
                log(instance.id, "[SUCCESS] Process started (PID: $pid)")
            }

            // Store runtime data
            val runtime = tunnelInstances[instance.id] ?: TunnelInstanceRuntime(instance)
            runtime.process = process
            runtime.pid = getPid(process)
            tunnelInstances[instance.id] = runtime

            // Set running BEFORE starting log job
            instance.isRunning = true

            // Save running state immediately
            saveRunningState()

            // Notify UI immediately
            scope.launch(Dispatchers.Main) {
                statusCallback?.invoke(instance.id, true)
            }

            // Start log reader
            runtime.logJob = scope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        log(instance.id, line)
                        parseTunnelUrl(instance.id, line)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Log reader error for ${instance.id}", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        log(instance.id, "[INFO] Tunnel process ended")
                        stopTunnel(instance.id, false)
                    }
                }
            }

            // Start process monitor
            runtime.monitorJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(5000)
                    try {
                        process.exitValue()
                        log(instance.id, "[WARN] Process died unexpectedly")
                        withContext(Dispatchers.Main) {
                            stopTunnel(instance.id, false)
                        }
                        break
                    } catch (e: IllegalThreadStateException) {
                        // Still alive
                    }
                }
            }

            // Start foreground service
            startForegroundService(instance)

            log(instance.id, "[INFO] Waiting for tunnel URL (10-30s)...")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel ${instance.id}", e)
            log(instance.id, "[ERROR] ${e.message}")
            instance.isRunning = false

            syncToFirebase(instance, "failed")
            saveRunningState()

            scope.launch(Dispatchers.Main) {
                statusCallback?.invoke(instance.id, false)
            }
            false
        }
    }

    // ═══════════════════════════════════════════════════
    //  STOP TUNNEL INSTANCE
    // ═══════════════════════════════════════════════════

    fun stopTunnel(instanceId: String, userInitiated: Boolean = true) {
        val runtime = tunnelInstances[instanceId] ?: return
        val instance = runtime.instance

        // ইতিমধ্যে বন্ধ হলে ডাবল-স্টপ এড়াই (log job-এর finally আবার ডাকে)
        if (!instance.isRunning && runtime.process == null && runtime.pid <= 0) return

        log(instanceId, if (userInitiated) "[INFO] Stopping..." else "[INFO] Tunnel process ended")

        runtime.logJob?.cancel()
        runtime.monitorJob?.cancel()

        val process = runtime.process
        val pid = runtime.pid

        // UI-স্টেট সাথে সাথে আপডেট — ভারী কাজ নিচে ব্যাকগ্রাউন্ডে
        instance.isRunning = false
        instance.isConnected = false
        instance.tunnelUrl = ""
        runtime.process = null
        runtime.pid = -1L

        syncToFirebase(instance, "stopped")
        saveRunningState()

        scope.launch(Dispatchers.Main) { statusCallback?.invoke(instanceId, false) }
        updateForegroundService()

        // মেইন থ্রেড ব্লক না করে প্রসেস কিল (আগে waitFor(3s) UI জমিয়ে দিত)
        scope.launch(Dispatchers.IO) {
            try {
                process?.destroy()
                val stopped = process?.waitFor(3, TimeUnit.SECONDS) ?: true
                if (!stopped) {
                    process?.destroyForcibly()
                    log(instanceId, "[WARN] Force killed")
                }
            } catch (e: Exception) {
                log(instanceId, "[WARN] Stop error: ${e.message}")
            }
            if (process == null && pid > 0) {
                try { android.os.Process.killProcess(pid.toInt()) } catch (_: Exception) {}
                try { Runtime.getRuntime().exec(arrayOf("kill", pid.toString())) } catch (_: Exception) {}
            }
            log(instanceId, "[INFO] Stopped")
        }
    }

    fun stopAllTunnels() {
        tunnelInstances.keys.forEach { stopTunnel(it) }
        stopForegroundService()
    }

    // ═══════════════════════════════════════════════════
    //  FOREGROUND SERVICE MANAGEMENT
    // ═══════════════════════════════════════════════════

    private fun startForegroundService(instance: TunnelInstance) {
        val intent = Intent(context, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
            putExtra("instance_id", instance.id)
            putExtra("instance_name", instance.name)
        }
        try { context.startForegroundService(intent) } catch (e: Exception) { Log.w(TAG, "startForegroundService failed: ${e.message}") }
    }

    private fun updateForegroundService() {
        val runningCount = getRunningInstances().size
        if (runningCount > 0) {
            val intent = Intent(context, TunnelService::class.java).apply {
                action = TunnelService.ACTION_UPDATE
                putExtra("running_count", runningCount)
            }
            try { context.startService(intent) } catch (e: Exception) { Log.w(TAG, "service update skipped: ${e.message}") }
        } else {
            stopForegroundService()
        }
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, TunnelService::class.java))
    }

    // ═══════════════════════════════════════════════════
    //  HELPER METHODS
    // ═══════════════════════════════════════════════════

    private fun isPortInUse(port: Int, excludeInstanceId: String): Boolean {
        return tunnelInstances.values.any {
            it.instance.id != excludeInstanceId &&
                    it.instance.isRunning &&
                    it.instance.config.localPort == port
        }
    }

    private fun getPid(process: Process): Long {
        // Process.pid() (API 33+) compile-time এ সবসময় দেখা যায় না — reflection দিয়ে ডাকি
        try {
            val m = process.javaClass.getMethod("pid")
            (m.invoke(process) as? Number)?.let { return it.toLong() }
        } catch (_: Throwable) { }
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process).toLong()
        } catch (e: Exception) {
            -1L
        }
    }

    private fun setupTermuxEnvironment(): File {
        val prefixDir = File(context.filesDir, "usr")
        val tmpDir = File(prefixDir, "tmp")
        val cfDir = File(context.filesDir, ".cloudflared")
        prefixDir.mkdirs()
        tmpDir.mkdirs()
        cfDir.mkdirs()
        return prefixDir
    }

    suspend fun runGenericCommand(cmdArgs: String, onProgress: ((String) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        val prefixDir = setupTermuxEnvironment()
        val binaryPath = getBinaryPath()
        val home = context.filesDir.absolutePath
        val tmpDir = File(prefixDir, "tmp").absolutePath

        val script = """#!/system/bin/sh
export PREFIX="${prefixDir.absolutePath}"
export HOME="$home"
export TMPDIR="$tmpDir"
export PATH="/system/bin:/system/xbin"
export SSL_CERT_DIR="/system/etc/security/cacerts"

cd "$home"
exec "$binaryPath" $cmdArgs 2>&1
"""
        val cmdFile = File(context.filesDir, "last_command.sh")
        cmdFile.writeText(script)
        cmdFile.setExecutable(true, false)
        nativeSetPermissions(cmdFile.absolutePath)

        val fullOutput = StringBuilder()
        try {
            val pb = ProcessBuilder("/system/bin/sh", cmdFile.absolutePath).apply {
                environment()["HOME"] = home
                environment()["TMPDIR"] = tmpDir
                environment()["PREFIX"] = prefixDir.absolutePath
                redirectErrorStream(true)
            }
            val process = pb.start()
            
            val reader = process.inputStream.bufferedReader()
            val startTime = System.currentTimeMillis()
            
            while (true) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    fullOutput.append(line).append("\n")
                    onProgress?.invoke(line)
                    log("SYSTEM", "[CMD] $line")
                } else {
                    try {
                        process.exitValue()
                        break
                    } catch (e: IllegalThreadStateException) {
                        delay(200)
                    }
                }
                
                // Allow login command to run longer
                val timeout = if (cmdArgs.contains("login")) 120000L else 45000L
                if (System.currentTimeMillis() - startTime > timeout) {
                    process.destroy()
                    fullOutput.append("\n[TIMEOUT] Command terminated.")
                    break
                }
            }
            
            fullOutput.toString().ifBlank { "Command executed with no output" }
        } catch (e: Exception) {
            val err = "[ERROR] ${e.message}"
            log("SYSTEM", err)
            fullOutput.append(err)
            fullOutput.toString()
        }
    }

    private fun createWrapperScript(instance: TunnelInstance, prefixDir: File): File {
        val binaryPath = getBinaryPath()
        val home = context.filesDir.absolutePath
        val tmpDir = File(prefixDir, "tmp").absolutePath
        val config = instance.config

        val args = buildString {
            append("tunnel")
            
            // 1. Global/Common Flags first
            append(" --no-autoupdate")
            if (config.logLevel != "info") append(" --loglevel ${config.logLevel}")
            if (config.region != "auto") append(" --region ${config.region}")
            if (config.enableMetrics) append(" --metrics localhost:${config.metricsPort}")

            if (config.tunnelType == TunnelType.NAMED) {
                // 2. Command
                append(" run")
                
                // 3. Command-specific flags
                if (config.credentialsFile.isNotBlank()) {
                    // Smart path resolution for credentials
                    val credFile = when {
                        config.credentialsFile.startsWith("/") -> File(config.credentialsFile)
                        File(context.filesDir, config.credentialsFile).exists() -> File(context.filesDir, config.credentialsFile)
                        else -> File(File(context.filesDir, ".cloudflared"), config.credentialsFile)
                    }
                    
                    if (!credFile.exists()) {
                        log(instance.id, "[ERROR] Credentials file not found. Checked: ${credFile.absolutePath}")
                    }
                    
                    append(" --credentials-file \"${credFile.absolutePath}\"")
                }

                // Add the local URL mapping for the named tunnel
                // This prevents the "No ingress rules were defined" 503 error
                if (config.protocol == "https") append(" --no-tls-verify")
                append(" --url ${config.protocol}://localhost:${config.localPort}")
                
                // 4. Positional Argument (Tunnel Name/ID) MUST be last
                append(" \"${config.tunnelName.ifBlank { config.tunnelId }}\"")
            } else {
                if (config.protocol == "https") append(" --no-tls-verify")
                append(" --url ${config.protocol}://localhost:${config.localPort}")
            }
        }

        val script = """#!/system/bin/sh
export PREFIX="${prefixDir.absolutePath}"
export HOME="$home"
export TMPDIR="$tmpDir"
export PATH="/system/bin:/system/xbin"
export SSL_CERT_DIR="/system/etc/security/cacerts"

echo "=========================================="
echo "Cloudflare Tunnel: ${instance.name}"
echo "=========================================="
date
echo "Binary: $binaryPath"
echo "Args: $args"
echo "=========================================="

cd "$home"
exec "$binaryPath" $args 2>&1
"""

        val wrapperFile = File(getWrapperPath(instance.id))
        wrapperFile.writeText(script)
        wrapperFile.setExecutable(true, false)
        nativeSetPermissions(wrapperFile.absolutePath)

        return wrapperFile
    }

    private fun parseTunnelUrl(instanceId: String, line: String) {
        val inst = tunnelInstances[instanceId]?.instance
        // Named tunnel: কানেকশন রেজিস্টার হলেই "LIVE"
        if (inst != null && inst.config.tunnelType == TunnelType.NAMED && !inst.isConnected &&
            (line.contains("Registered tunnel connection", true) || line.contains("Connection registered", true))
        ) {
            inst.isConnected = true
            log(instanceId, "[SUCCESS] ✓ Named tunnel connected: ${inst.tunnelUrl}")
            syncToFirebase(inst, "running", inst.tunnelUrl)
            scope.launch(Dispatchers.Main) {
                tunnelUrlCallback?.invoke(instanceId, inst.tunnelUrl)
                statusCallback?.invoke(instanceId, true)
            }
        }
        val regex = Regex("""https://([a-z0-9-]+\.trycloudflare\.com)""")
        val match = regex.find(line) ?: return
        val url = match.value

        if (url.contains("api.trycloudflare.com")) return

        val instance = tunnelInstances[instanceId]?.instance ?: return
        if (url == instance.tunnelUrl) return

        // Update instance
        instance.tunnelUrl = url
        instance.isConnected = true

        // Save state
        saveRunningState()

        log(instanceId, "[SUCCESS] ✓ Tunnel URL: $url")

        // Sync to Firebase
        syncToFirebase(instance, "running", url)

        // Notify UI on main thread
        scope.launch(Dispatchers.Main) {
            tunnelUrlCallback?.invoke(instanceId, url)
        }
    }

    private fun log(instanceId: String, msg: String) {
        val logEntry = LogEntry(
            message = msg,
            type = when {
                msg.contains("[ERROR]", true) -> LogType.ERROR
                msg.contains("[WARN]", true) -> LogType.WARN
                msg.contains("[SUCCESS]", true) -> LogType.SUCCESS
                msg.contains("[DEBUG]", true) -> LogType.DEBUG
                else -> LogType.INFO
            }
        )

        // Add to instance logs (একাধিক থ্রেড থেকে আসে — সিঙ্ক্রোনাইজড)
        tunnelInstances[instanceId]?.instance?.logs?.let { logs ->
            synchronized(logs) {
                logs.add(logEntry)
                while (logs.size > 500) logs.removeAt(0)
            }
        }

        Log.d(TAG, "[$instanceId] $msg")
        scope.launch(Dispatchers.Main) {
            if (instanceId == "SYSTEM") {
                globalLogCallback?.invoke(msg)
            } else {
                instanceLogCallback?.invoke(instanceId, logEntry)
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  BINARY INSTALLATION & UPDATE
    // ═══════════════════════════════════════════════════

    suspend fun installBinary(
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {

        val binaryFile = File(getBinaryPath())

        try {
            if (isBinaryInstalled()) {
                log("SYSTEM", "[INFO] Binary exists (${binaryFile.length() / 1_048_576} MB)")
                chmodBinary(binaryFile)
                val ver = queryVersion()
                withContext(Dispatchers.Main) { onProgress(100); onComplete(true, ver) }
                return@withContext
            }

            val assetList = context.assets.list("") ?: emptyArray()
            log("SYSTEM", "[INFO] Checking assets")

            val assetName = listOf("cloudflared.bin", "cloudflared", "cloudflared-termux")
                .firstOrNull { it in assetList }

            if (assetName != null) {
                log("SYSTEM", "[INFO] Installing from bundled asset: $assetName")
                installFromAsset(assetName, binaryFile, onProgress, onComplete)
            } else {
                log("SYSTEM", "[INFO] Downloading from Termux repository...")
                binaryDownloader.setLogCallback { msg ->
                    scope.launch(Dispatchers.Main) { globalLogCallback?.invoke(msg) }
                }

                binaryDownloader.downloadAndInstall(
                    onProgress = { progress, status ->
                        onProgress(progress)
                        globalLogCallback?.invoke("[DOWNLOAD] $status")
                    },
                    onComplete = { success, message ->
                        if (success) {
                            setupTermuxEnvironment()
                            val ver = queryVersion()
                            onComplete(true, ver.ifBlank { message })
                        } else {
                            onComplete(false, message)
                        }
                    }
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            log("SYSTEM", "[ERROR] ${e.message}")
            withContext(Dispatchers.Main) {
                onComplete(false, "[ERROR] ${e.message}")
            }
        }
    }

    suspend fun updateBinary(
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {

        log("SYSTEM", "[INFO] Updating cloudflared binary...")

        val wasRunning = getRunningInstances().map { it.id }
        if (wasRunning.isNotEmpty()) {
            log("SYSTEM", "[INFO] Stopping ${wasRunning.size} running tunnel(s)...")
            stopAllTunnels()
            delay(2000)
        }

        val binaryFile = File(getBinaryPath())
        if (binaryFile.exists()) {
            binaryFile.delete()
            log("SYSTEM", "[INFO] Removed old binary")
        }

        binaryDownloader.setLogCallback { msg ->
            scope.launch(Dispatchers.Main) { globalLogCallback?.invoke(msg) }
        }

        binaryDownloader.downloadAndInstall(
            onProgress = { progress, status ->
                onProgress(progress)
                globalLogCallback?.invoke("[UPDATE] $status")
            },
            onComplete = { success, message ->
                if (success) {
                    setupTermuxEnvironment()
                    val ver = queryVersion()
                    onComplete(true, "Updated to: ${ver.ifBlank { "latest version" }}")

                    if (wasRunning.isNotEmpty()) {
                        log("SYSTEM", "[INFO] Restart tunnels manually if needed")
                    }
                } else {
                    onComplete(false, message)
                }
            }
        )
    }

    private suspend fun installFromAsset(
        assetName: String,
        binaryFile: File,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (binaryFile.exists()) binaryFile.delete()

            context.assets.open(assetName).use { input ->
                FileOutputStream(binaryFile).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    var lastPct = 0

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        total += read

                        val pct = minOf(95, (total / 300_000).toInt())
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }

            if (binaryFile.length() < 1_000_000L) {
                binaryFile.delete()
                withContext(Dispatchers.Main) {
                    onComplete(false, "[ERROR] File too small - corrupted")
                }
                return@withContext
            }

            chmodBinary(binaryFile)
            setupTermuxEnvironment()

            val ver = queryVersion()
            log("SYSTEM", "[SUCCESS] Installed from asset")

            withContext(Dispatchers.Main) {
                onProgress(100)
                onComplete(true, ver.ifBlank { "Binary installed (${binaryFile.length() / 1_048_576} MB)" })
            }

        } catch (e: Exception) {
            log("SYSTEM", "[ERROR] Asset installation failed: ${e.message}")
            withContext(Dispatchers.Main) {
                onComplete(false, "[ERROR] ${e.message}")
            }
        }
    }

    private fun chmodBinary(file: File) {
        file.setExecutable(true, false)
        file.setReadable(true, false)
        file.setWritable(true, true)
        nativeSetPermissions(file.absolutePath)
    }

    private fun queryVersion(): String {
        return try {
            val pb = ProcessBuilder(getBinaryPath(), "--version").apply {
                redirectErrorStream(true)
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            ""
        }
    }

    fun destroy() {
        stopAllTunnels()
        scope.cancel()
    }
}
