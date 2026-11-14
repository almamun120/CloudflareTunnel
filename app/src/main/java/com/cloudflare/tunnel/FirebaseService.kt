package com.cloudflare.tunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Firebase Realtime Database REST API client
 * No Firebase SDK required - uses pure HTTP requests
 */
class FirebaseService(
    private val databaseUrl: String,
    private val apiKey: String = ""
) {
    companion object {
        private const val TAG = "FirebaseService"
    }

    private var logCallback: ((String) -> Unit)? = null

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback?.invoke(msg)
    }

    /**
     * Build Firebase REST API URL
     */
    private fun buildUrl(path: String): String {
        val cleanUrl = databaseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        var url = "$cleanUrl/$cleanPath.json"

        if (apiKey.isNotBlank()) {
            url += "?auth=" + java.net.URLEncoder.encode(apiKey, "UTF-8")
        }

        return url
    }

    /**
     * Test Firebase connection
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        if (databaseUrl.isBlank()) {
            log("[FIREBASE] No database URL configured")
            return@withContext false
        }

        try {
            log("[FIREBASE] Testing connection...")

            val testData = JSONObject().apply {
                put("test", true)
                put("timestamp", getCurrentTimestamp())
            }

            val result = putData("test", testData)

            if (result) {
                log("[FIREBASE] ✓ Connection successful")
            } else {
                log("[FIREBASE] ✗ Connection failed")
            }

            result

        } catch (e: Exception) {
            log("[FIREBASE] ✗ Connection error: ${e.message}")
            false
        }
    }

    /**
     * Save tunnel data to Firebase (PUT - overwrites)
     */
    suspend fun saveTunnel(tunnelId: String, tunnelData: TunnelFirebaseData): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("url", tunnelData.url)
                    put("name", tunnelData.name)
                    put("local_port", tunnelData.localPort)
                    put("protocol", tunnelData.protocol)
                    put("status", tunnelData.status)
                    if (tunnelData.status == "starting") put("created_at", tunnelData.createdAt)
                    put("updated_at", tunnelData.updatedAt)
                    put("is_healthy", tunnelData.isHealthy)
                    put("health_check_count", tunnelData.healthCheckCount)
                }

                val result = patchData("tunnels/$tunnelId", json)

                if (result) {
                    log("[FIREBASE] ✓ Tunnel data saved")
                } else {
                    log("[FIREBASE] ✗ Failed to save tunnel data")
                }

                result

            } catch (e: Exception) {
                log("[FIREBASE] ✗ Error saving tunnel: ${e.message}")
                false
            }
        }

    /**
     * Update tunnel status
     */
    suspend fun updateTunnelStatus(
        tunnelId: String,
        status: String,
        url: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("status", status)
                put("updated_at", getCurrentTimestamp())
                if (url.isNotBlank()) {
                    put("url", url)
                }
            }

            patchData("tunnels/$tunnelId", json)

        } catch (e: Exception) {
            log("[FIREBASE] Error updating status: ${e.message}")
            false
        }
    }

    /**
     * Update health status
     */
    suspend fun updateHealthStatus(
        tunnelId: String,
        isHealthy: Boolean,
        checkCount: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("is_healthy", isHealthy)
                put("health_check_count", checkCount)
                put("updated_at", getCurrentTimestamp())
            }

            patchData("tunnels/$tunnelId", json)

        } catch (e: Exception) {
            log("[FIREBASE] Error updating health: ${e.message}")
            false
        }
    }

    /**
     * Add tunnel to history
     */
    suspend fun addToHistory(tunnelData: TunnelFirebaseData): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("url", tunnelData.url)
                    put("name", tunnelData.name)
                    put("local_port", tunnelData.localPort)
                    put("created_at", tunnelData.createdAt)
                }

                postData("tunnel_history", json)

            } catch (e: Exception) {
                log("[FIREBASE] Error adding to history: ${e.message}")
                false
            }
        }

    /**
     * Delete tunnel data
     */
    suspend fun deleteTunnel(tunnelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            deleteData("tunnels/$tunnelId")
        } catch (e: Exception) {
            log("[FIREBASE] Error deleting tunnel: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════
    //  HTTP METHODS
    // ═══════════════════════════════════════════════════

    /**
     * PUT request (overwrites data)
     */
    private fun putData(path: String, data: JSONObject): Boolean {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(buildUrl(path))
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }

            connection.outputStream.use { os ->
                os.write(data.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = connection.responseCode
            return responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "PUT error", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * PATCH request (merges data)
     */
    /** Android HttpURLConnection-এ PATCH সব ডিভাইসে নির্ভরযোগ্য নয় — Firebase-এর X-HTTP-Method-Override ব্যবহার */
    private fun patchData(path: String, data: JSONObject): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(buildUrl(path))
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connectTimeout = 10000
                readTimeout = 10000
            }
            connection.outputStream.use { os ->
                os.write(data.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }
            return connection.responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "PATCH error", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun postData(path: String, data: JSONObject): Boolean {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(buildUrl(path))
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }

            connection.outputStream.use { os ->
                os.write(data.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = connection.responseCode
            return responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "POST error", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * DELETE request
     */
    private fun deleteData(path: String): Boolean {
        var connection: HttpURLConnection? = null

        try {
            val url = URL(buildUrl(path))
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "DELETE"
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = connection.responseCode
            return responseCode in 200..299

        } catch (e: Exception) {
            Log.e(TAG, "DELETE error", e)
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}

/**
 * Data class for Firebase tunnel data
 */
data class TunnelFirebaseData(
    val url: String,
    val name: String,
    val localPort: Int,
    val protocol: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val isHealthy: Boolean = false,
    val healthCheckCount: Int = 0
)
