package com.cloudflare.tunnel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cloudflare.tunnel.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tunnelManager: TunnelManager
    private lateinit var tunnelAdapter: TunnelAdapter
    private lateinit var logAdapter: LogAdapter

    private var selectedInstanceId: String? = null
    private var isAutoScroll = true

    /** সব ইনস্ট্যান্সের লগ একত্রে (id → entry); selection বদলালে পুনরায় ফিল্টার হয় */
    private val allLogs = ArrayDeque<Pair<String, LogEntry>>()

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Notification permission denied — Android may stop the tunnel in background")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tunnelManager = (application as App).tunnelManager

        setupToolbar()
        setupLists()
        setupCallbacks()
        setupButtons()

        requestNotificationPermission()
        maybeAskBatteryOptimization()

        postSystemLog("[INFO] Cloudflare Tunnel ready — ${tunnelManager.nativeGetVersion()}")
        if (!tunnelManager.isBinaryInstalled()) postSystemLog("[WARN] cloudflared not installed. Tap Install.")

        refreshAll()
    }

    // ── Setup ────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.overflowIcon?.setTint(ContextCompat.getColor(this, R.color.colorText))
        binding.toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
    }

    private fun setupLists() {
        tunnelAdapter = TunnelAdapter(
            onStart = { startTunnel(it) },
            onStop = { stopTunnel(it) },
            onSelect = { selectInstance(it) },
            onDelete = { deleteTunnel(it) },
            onCopyUrl = { copyUrl(it) },
            onQr = { showQr(it) }
        )
        binding.rvTunnels.layoutManager = LinearLayoutManager(this)
        binding.rvTunnels.adapter = tunnelAdapter
        binding.rvTunnels.itemAnimator = null

        logAdapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter
        binding.rvLogs.itemAnimator = null
    }

    private fun setupCallbacks() {
        // একটাই লগ কলব্যাক — আগে global+instance দুটোই যুক্ত থাকায় প্রতিটি লাইন ডুপ্লিকেট দেখাত
        tunnelManager.setInstanceLogCallback { instanceId, entry ->
            runOnUiThread { onLog(instanceId, entry) }
        }
        tunnelManager.setGlobalLogCallback { }
        tunnelManager.setTunnelUrlCallback { instanceId, url ->
            runOnUiThread {
                tunnelManager.getInstance(instanceId)?.let { tunnelAdapter.updateItem(it) }
                updateStatus()
            }
        }
        tunnelManager.setStatusCallback { instanceId, _ ->
            runOnUiThread {
                tunnelManager.getInstance(instanceId)?.let { tunnelAdapter.updateItem(it) }
                updateStatus()
            }
        }
    }

    private fun setupButtons() {
        binding.btnAddTunnel.setOnClickListener { showAddTunnelDialog() }
        binding.btnInstall.setOnClickListener {
            if (tunnelManager.isBinaryInstalled()) updateBinary() else installBinary()
        }
        binding.btnClearLog.setOnClickListener {
            allLogs.clear()
            logAdapter.clearLogs()
        }
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked -> isAutoScroll = checked }
    }

    // ── Logging ──────────────────────────────────────────────────────────

    private fun postSystemLog(msg: String) {
        onLog("SYSTEM", LogEntry(msg, type = LogAdapter.classify(msg)))
    }

    private fun onLog(instanceId: String, entry: LogEntry) {
        allLogs.addLast(instanceId to entry)
        while (allLogs.size > 1000) allLogs.removeFirst()
        if (selectedInstanceId == null || selectedInstanceId == instanceId || instanceId == "SYSTEM") {
            logAdapter.addLog(entry.message, entry.type)
            if (isAutoScroll && logAdapter.itemCount > 0) {
                binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
            }
        }
    }

    private fun rebuildLogs() {
        val sel = selectedInstanceId
        val entries = allLogs.filter { sel == null || it.first == sel || it.first == "SYSTEM" }.map { it.second }
        logAdapter.setLogs(entries)
        if (logAdapter.itemCount > 0) binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        binding.tvLogOwner.text = sel?.let { tunnelManager.getInstance(it)?.name } ?: "All"
    }

    // ── Tunnel operations ────────────────────────────────────────────────

    private fun showAddTunnelDialog() {
        if (!tunnelManager.isBinaryInstalled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("cloudflared not installed")
                .setMessage("Install the cloudflared binary first (about 30 MB download).")
                .setPositiveButton("Install now") { _, _ -> installBinary() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_add_tunnel, null)
        val etName = view.findViewById<EditText>(R.id.etTunnelName)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val spProtocol = view.findViewById<Spinner>(R.id.spinnerProtocol)

        // Quick tunnel শুধু HTTP/HTTPS সাপোর্ট করে (আগে tcp/ssh ছিল — কাজ করত না)
        val protocols = arrayOf("http", "https")
        spProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocols)

        MaterialAlertDialogBuilder(this)
            .setTitle("New tunnel")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val port = etPort.text.toString().trim().toIntOrNull() ?: 0
                if (port !in 1..65535) {
                    toast("Port must be between 1 and 65535")
                    return@setPositiveButton
                }
                val name = etName.text.toString().trim().ifBlank { "Tunnel :$port" }
                val instance = TunnelInstance(
                    name = name,
                    config = TunnelConfig(localPort = port, protocol = protocols[spProtocol.selectedItemPosition])
                )
                tunnelManager.addInstance(instance)
                refreshAll()
                postSystemLog("[INFO] Created tunnel \"$name\" → localhost:$port")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTunnel(instance: TunnelInstance) {
        if (!tunnelManager.isNetworkAvailable()) {
            showNetworkError()
            return
        }
        postSystemLog("[INFO] Starting ${instance.name}…")
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = tunnelManager.startTunnel(instance)
            runOnUiThread {
                tunnelAdapter.updateItem(instance)
                updateStatus()
                if (!ok) toast("Failed to start — see console")
            }
        }
    }

    private fun stopTunnel(instance: TunnelInstance) {
        tunnelManager.stopTunnel(instance.id)    // এখন নন-ব্লকিং
        tunnelAdapter.updateItem(instance)
        updateStatus()
    }

    private fun deleteTunnel(instance: TunnelInstance) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete tunnel")
            .setMessage("Delete “${instance.name}”? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                tunnelManager.removeTunnel(instance.id)
                if (selectedInstanceId == instance.id) {
                    selectedInstanceId = null
                    tunnelAdapter.selectedId = null
                    rebuildLogs()
                }
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectInstance(instance: TunnelInstance) {
        selectedInstanceId = if (selectedInstanceId == instance.id) null else instance.id
        tunnelAdapter.selectedId = selectedInstanceId
        rebuildLogs()
    }

    private fun copyUrl(instance: TunnelInstance) {
        if (instance.tunnelUrl.isBlank()) {
            toast("No URL yet — tunnel is still starting")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Tunnel URL", instance.tunnelUrl))
        toast("URL copied")
    }

    private fun showQr(instance: TunnelInstance) {
        if (instance.tunnelUrl.isBlank()) {
            toast("No URL yet — tunnel is still starting")
            return
        }
        startActivity(Intent(this, QRCodeActivity::class.java).putExtra("tunnel_url", instance.tunnelUrl))
    }

    // ── UI refresh ───────────────────────────────────────────────────────

    private fun refreshAll() {
        val instances = tunnelManager.getAllInstances()
        tunnelAdapter.submitList(instances)
        binding.tvNoTunnels.visibility = if (instances.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTunnels.visibility = if (instances.isEmpty()) View.GONE else View.VISIBLE
        updateStatus()
        refreshBinaryCard()
    }

    private fun updateStatus() {
        val running = tunnelManager.getRunningInstances().size
        val total = tunnelManager.getAllInstances().size
        binding.tvStatus.text = when {
            running > 0 -> "$running of $total LIVE"
            total > 0 -> "$total TUNNEL${if (total > 1) "S" else ""} · IDLE"
            else -> "NO TUNNELS"
        }
        binding.tvStatus.setTextColor(Color.parseColor(if (running > 0) "#34D399" else "#8C98B5"))
    }

    private fun refreshBinaryCard() {
        if (tunnelManager.isBinaryInstalled()) {
            val mb = java.io.File(tunnelManager.getBinaryPath()).length() / 1_048_576
            binding.tvBinaryTitle.text = "cloudflared ready"
            binding.tvBinarySub.text = "Installed · $mb MB · arch ${BinaryDownloader.termuxArch()}"
            binding.btnInstall.text = "Update"
        } else {
            binding.tvBinaryTitle.text = "cloudflared not installed"
            binding.tvBinarySub.text = "Required to create tunnels (~30 MB)"
            binding.btnInstall.text = "Install"
        }
    }

    // ── Permissions / battery ────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** একবারই জিজ্ঞেস করি — আগে প্রতিবার অ্যাপ খুললে সিস্টেম ডায়ালগ আসত */
    private fun maybeAskBatteryOptimization() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Keep tunnels alive")
            .setMessage("Allow the app to ignore battery optimization so tunnels keep running with the screen off.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    toast("Could not open battery settings")
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showNetworkError() {
        MaterialAlertDialogBuilder(this)
            .setTitle("No internet connection")
            .setMessage("Cloudflare Tunnel needs an active internet connection.")
            .setPositiveButton("Wi-Fi settings") { _, _ -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Binary install/update ────────────────────────────────────────────

    private fun installBinary() {
        if (!tunnelManager.isNetworkAvailable()) { showNetworkError(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Install cloudflared")
            .setMessage("Downloads the latest cloudflared build from the Termux repository (~30–50 MB).")
            .setPositiveButton("Download") { _, _ -> performBinaryInstall(update = false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBinary() {
        if (!tunnelManager.isNetworkAvailable()) { showNetworkError(); return }
        val running = tunnelManager.getRunningInstances().size
        val msg = if (running > 0) "This will stop $running running tunnel(s) and download the latest version."
        else "Download and install the latest cloudflared version?"
        MaterialAlertDialogBuilder(this)
            .setTitle("Update cloudflared")
            .setMessage(msg)
            .setPositiveButton("Update") { _, _ -> performBinaryInstall(update = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performBinaryInstall(update: Boolean) {
        binding.btnAddTunnel.isEnabled = false
        binding.btnInstall.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.setProgressCompat(0, false)

        val onProgress: (Int) -> Unit = { pct -> runOnUiThread { binding.progressBar.setProgressCompat(pct, true) } }
        val onComplete: (Boolean, String) -> Unit = { ok, msg ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                binding.btnAddTunnel.isEnabled = true
                binding.btnInstall.isEnabled = true
                refreshAll()
                if (ok) {
                    postSystemLog("[SUCCESS] $msg")
                    toast(if (update) "Updated" else "Installed")
                } else {
                    postSystemLog("[ERROR] $msg")
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (update) "Update failed" else "Installation failed")
                        .setMessage("$msg\n\n• Check your internet connection\n• Make sure there is free storage\n• Try again in a few minutes")
                        .setPositiveButton("Retry") { _, _ -> performBinaryInstall(update) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            if (update) tunnelManager.updateBinary(onProgress, onComplete)
            else tunnelManager.installBinary(onProgress, onComplete)
        }
    }

    // ── Menu ─────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_install_binary -> { installBinary(); true }
            R.id.action_update_binary -> {
                if (!tunnelManager.isBinaryInstalled()) toast("Install cloudflared first") else updateBinary()
                true
            }
            R.id.action_stop_all -> {
                val running = tunnelManager.getRunningInstances().size
                if (running == 0) toast("No running tunnels")
                else MaterialAlertDialogBuilder(this)
                    .setTitle("Stop all tunnels")
                    .setMessage("Stop $running running tunnel(s)?")
                    .setPositiveButton("Stop all") { _, _ ->
                        tunnelManager.stopAllTunnels()
                        refreshAll()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_share_logs -> {
                val logs = logAdapter.getAllLogs()
                if (logs.isBlank()) toast("No logs to share")
                else startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, logs)
                        putExtra(Intent.EXTRA_SUBJECT, "Cloudflare Tunnel logs")
                    }, "Share logs"
                ))
                true
            }
            R.id.action_about -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Cloudflare Tunnel v${BuildConfig.VERSION_NAME}")
                    .setMessage(
                        "NDK: ${tunnelManager.nativeGetVersion()}\n" +
                        "Tunnels: ${tunnelManager.getRunningInstances().size}/${tunnelManager.getAllInstances().size} live\n" +
                        "cloudflared: ${if (tunnelManager.isBinaryInstalled()) "installed" else "not installed"}\n\n" +
                        "Developer: Abdullah Al Mamun\nDhaka, Bangladesh\nmamun995599@gmail.com\n+8801945120109"
                    )
                    .setPositiveButton("OK", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        // কলব্যাক ছেড়ে দিই — singleton manager আর মৃত Activity ধরে রাখবে না
        tunnelManager.setInstanceLogCallback { _, _ -> }
        tunnelManager.setTunnelUrlCallback { _, _ -> }
        tunnelManager.setStatusCallback { _, _ -> }
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
