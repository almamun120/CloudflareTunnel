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
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tunnelManager: TunnelManager
    private lateinit var tunnelAdapter: TunnelAdapter
    private lateinit var logAdapter: LogAdapter

    private var selectedInstanceId: String? = null
    private var isAutoScroll = true

    /** সব ইনস্ট্যান্সের লগ (id → entry); selection বদলালে নতুন করে ফিল্টার হয় */
    private val allLogs = ArrayDeque<Pair<String, LogEntry>>()

    /** চলমান ডায়ালগের credentials ফিল্ড (ফাইল পিকার ফলাফল বসাতে) */
    private var etCredentialsRef: EditText? = null
    private var backupIncludeKeys = false

    // ── Activity Result launchers ────────────────────────────────────────
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Notification permission denied — Android may stop tunnels in background")
    }
    private val credentialsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importCredentials(it) }
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { doBackup(it) }
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { confirmRestore(it) }
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

    // ═════════════════════════════════════════════════════════════════════
    //  Setup
    // ═════════════════════════════════════════════════════════════════════

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
            onQr = { showQr(it) },
            onEdit = { showTunnelDialog(it) }
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
        tunnelManager.setGlobalLogCallback { msg -> runOnUiThread { postSystemLog(msg) } }
        tunnelManager.setInstanceLogCallback { instanceId, entry -> runOnUiThread { onLog(instanceId, entry) } }
        tunnelManager.setTunnelUrlCallback { instanceId, _ ->
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
        binding.btnAddTunnel.setOnClickListener { showTunnelDialog(null) }
        binding.btnInstall.setOnClickListener {
            if (tunnelManager.isBinaryInstalled()) updateBinary() else installBinary()
        }
        binding.btnClearLog.setOnClickListener {
            allLogs.clear()
            logAdapter.clearLogs()
        }
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked -> isAutoScroll = checked }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Logging
    // ═════════════════════════════════════════════════════════════════════

    private fun postSystemLog(msg: String) = onLog("SYSTEM", LogEntry(msg, type = LogAdapter.classify(msg)))

    private fun onLog(instanceId: String, entry: LogEntry) {
        allLogs.addLast(instanceId to entry)
        while (allLogs.size > 1000) allLogs.removeFirst()
        if (selectedInstanceId == null || selectedInstanceId == instanceId || instanceId == "SYSTEM") {
            logAdapter.addLog(entry.message, entry.type)
            if (isAutoScroll && logAdapter.itemCount > 0) binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun rebuildLogs() {
        val sel = selectedInstanceId
        logAdapter.setLogs(allLogs.filter { sel == null || it.first == sel || it.first == "SYSTEM" }.map { it.second })
        if (logAdapter.itemCount > 0) binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        binding.tvLogOwner.text = sel?.let { tunnelManager.getInstance(it)?.name } ?: "All"
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Add / Edit tunnel dialog (Quick + Named + Firebase)
    // ═════════════════════════════════════════════════════════════════════

    /** শেল-সেফ: নাম/হোস্টনেম/ফাইলে শুধু এই অক্ষরগুলো (কমান্ডে সরাসরি বসে, তাই ইনজেকশন আটকাই) */
    private fun isSafeToken(s: String) = Regex("^[A-Za-z0-9._:/@-]*$").matches(s)

    private fun showTunnelDialog(existing: TunnelInstance?) {
        if (existing != null && existing.isRunning) {
            toast("Stop the tunnel before editing")
            return
        }
        if (existing == null && !tunnelManager.isBinaryInstalled()) {
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
        val rgType = view.findViewById<RadioGroup>(R.id.rgTunnelType)
        val layoutNamed = view.findViewById<LinearLayout>(R.id.layoutNamedTunnel)
        val etCfName = view.findViewById<EditText>(R.id.etCfTunnelName)
        val etCfId = view.findViewById<EditText>(R.id.etCfTunnelId)
        val etHost = view.findViewById<EditText>(R.id.etPublicHostname)
        val etCred = view.findViewById<EditText>(R.id.etCredentialsPath)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val spProtocol = view.findViewById<Spinner>(R.id.spinnerProtocol)
        val swFirebase = view.findViewById<CompoundButton>(R.id.switchFirebaseSync)
        val etFbUrl = view.findViewById<EditText>(R.id.etFirebaseDatabaseUrl)
        val etFbKey = view.findViewById<EditText>(R.id.etFirebaseApiKey)

        val protocols = arrayOf("http", "https", "tcp", "ssh")
        spProtocol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocols)

        fun applyType(named: Boolean) { layoutNamed.visibility = if (named) View.VISIBLE else View.GONE }
        rgType.setOnCheckedChangeListener { _, id -> applyType(id == R.id.rbNamedTunnel) }
        swFirebase.setOnCheckedChangeListener { _, on -> etFbUrl.isEnabled = on; etFbKey.isEnabled = on }

        // ── আগের মান ──
        if (existing != null) {
            val c = existing.config
            etName.setText(existing.name)
            val named = c.tunnelType == TunnelType.NAMED
            rgType.check(if (named) R.id.rbNamedTunnel else R.id.rbQuickTunnel)
            applyType(named)
            etCfName.setText(c.tunnelName); etCfId.setText(c.tunnelId)
            etHost.setText(c.hostname); etCred.setText(c.credentialsFile)
            etPort.setText(c.localPort.toString())
            spProtocol.setSelection(protocols.indexOf(c.protocol).coerceAtLeast(0))
            swFirebase.isChecked = c.enableFirebaseSync
            etFbUrl.setText(c.firebaseDatabaseUrl); etFbKey.setText(c.firebaseApiKey)
        }
        etFbUrl.isEnabled = swFirebase.isChecked
        etFbKey.isEnabled = swFirebase.isChecked

        // ── Named tunnel সহায়ক বাটন ──
        view.findViewById<Button>(R.id.btnCloudflareLogin).setOnClickListener { runCloudflaredCommand("tunnel login") }
        view.findViewById<Button>(R.id.btnFindExistingTunnel).setOnClickListener { runCloudflaredCommand("tunnel list") }
        view.findViewById<Button>(R.id.btnBrowseCredentials).setOnClickListener {
            etCredentialsRef = etCred
            credentialsLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        view.findViewById<Button>(R.id.btnCreateTunnel).setOnClickListener {
            val name = etCfName.text.toString().trim()
            if (name.isBlank() || !isSafeToken(name)) { toast("Enter a valid tunnel name (letters, digits, - _ .)"); return@setOnClickListener }
            postSystemLog("[CMD] Creating tunnel: $name")
            lifecycleScope.launch(Dispatchers.IO) {
                val result = tunnelManager.runGenericCommand("tunnel create $name") { line -> runOnUiThread { postSystemLog("[CMD] $line") } }
                withContext(Dispatchers.Main) {
                    Regex("with id ([a-f0-9\\-]+)").find(result)?.let {
                        val id = it.groupValues[1]
                        etCfId.setText(id)
                        etCred.setText("$id.json")
                        toast("Tunnel created: $id")
                    }
                    showResult("Create tunnel", result)
                }
            }
        }
        view.findViewById<Button>(R.id.btnRouteDns).setOnClickListener {
            val name = etCfName.text.toString().trim()
            val host = etHost.text.toString().trim()
            if (name.isBlank() || host.isBlank() || !isSafeToken(name) || !isSafeToken(host)) {
                toast("Enter a valid tunnel name and hostname"); return@setOnClickListener
            }
            postSystemLog("[CMD] Routing DNS: $host → $name")
            lifecycleScope.launch(Dispatchers.IO) {
                val result = tunnelManager.runGenericCommand("tunnel route dns $name $host") { line -> runOnUiThread { postSystemLog("[CMD] $line") } }
                withContext(Dispatchers.Main) {
                    if (result.contains("1003") || result.contains("already exists", true)) {
                        showResult("DNS record exists", "A DNS record for '$host' already exists in Cloudflare. Delete the old record in the dashboard if this tunnel should take over.")
                    } else showResult("Route DNS", result)
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New tunnel" else "Edit tunnel")
            .setView(view)
            .setPositiveButton(if (existing == null) "Create" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val named = rgType.checkedRadioButtonId == R.id.rbNamedTunnel
                val port = etPort.text.toString().trim().toIntOrNull() ?: 0
                val cfName = etCfName.text.toString().trim()
                val cfId = etCfId.text.toString().trim()
                val host = etHost.text.toString().trim()
                val cred = etCred.text.toString().trim()

                if (port !in 1..65535) { toast("Port must be between 1 and 65535"); return@setOnClickListener }
                if (!isSafeToken(cfName) || !isSafeToken(cfId) || !isSafeToken(host) || !isSafeToken(cred)) {
                    toast("Tunnel name / ID / hostname / file may only contain letters, digits and - _ . : / @"); return@setOnClickListener
                }
                if (named && cfName.isBlank() && cfId.isBlank()) { toast("Named tunnel needs a tunnel name or ID"); return@setOnClickListener }
                if (swFirebase.isChecked && !etFbUrl.text.toString().trim().startsWith("https://")) {
                    toast("Firebase URL must start with https://"); return@setOnClickListener
                }

                val name = etName.text.toString().trim().ifBlank { existing?.name ?: "Tunnel :$port" }
                val protocol = protocols[spProtocol.selectedItemPosition]
                val cfg = TunnelConfig(
                    tunnelType = if (named) TunnelType.NAMED else TunnelType.QUICK,
                    tunnelName = if (named) cfName else "",
                    tunnelId = if (named) cfId else "",
                    credentialsFile = if (named) cred else "",
                    hostname = if (named) host else "",
                    localPort = port,
                    protocol = protocol,
                    firebaseDatabaseUrl = etFbUrl.text.toString().trim(),
                    firebaseApiKey = etFbKey.text.toString().trim(),
                    enableFirebaseSync = swFirebase.isChecked
                )
                if (existing == null) {
                    tunnelManager.addInstance(TunnelInstance(name = name, config = cfg))
                    postSystemLog("[INFO] ✓ Created tunnel: $name")
                } else {
                    existing.name = name
                    val c = existing.config
                    c.tunnelType = cfg.tunnelType; c.tunnelName = cfg.tunnelName; c.tunnelId = cfg.tunnelId
                    c.credentialsFile = cfg.credentialsFile; c.hostname = cfg.hostname
                    c.localPort = cfg.localPort; c.protocol = cfg.protocol
                    c.firebaseDatabaseUrl = cfg.firebaseDatabaseUrl; c.firebaseApiKey = cfg.firebaseApiKey
                    c.enableFirebaseSync = cfg.enableFirebaseSync
                    tunnelManager.updateInstance(existing)
                    postSystemLog("[INFO] ✓ Updated tunnel: $name (port $port)")
                }
                refreshAll()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun runCloudflaredCommand(cmd: String) {
        if (!tunnelManager.isBinaryInstalled()) { toast("Install cloudflared first"); return }
        postSystemLog("[CMD] cloudflared $cmd")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = tunnelManager.runGenericCommand(cmd) { line ->
                runOnUiThread { postSystemLog("[CMD] $line"); checkAndPromptUrl(line) }
            }
            withContext(Dispatchers.Main) { showResult("cloudflared $cmd", result.take(3000)) }
        }
    }

    private var lastPromptedUrl = ""
    private fun checkAndPromptUrl(line: String) {
        val url = Regex("(https?://[\\w.\\-/?=&%#]+)").find(line)?.value ?: return
        if (url.contains("api.trycloudflare.com") || url == lastPromptedUrl) return
        if (!url.contains("cloudflare", true)) return
        lastPromptedUrl = url
        MaterialAlertDialogBuilder(this)
            .setTitle("Authentication required")
            .setMessage("Open this Cloudflare login page?\n\n$url")
            .setPositiveButton("Open browser") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: Exception) { toast("Could not open browser") }
            }
            .setNeutralButton("Copy URL") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cloudflare URL", url))
                toast("URL copied")
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun showResult(title: String, msg: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg.ifBlank { "(no output)" }).setPositiveButton("OK", null).show()
    }

    // ── credentials import ───────────────────────────────────────────────

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) result = c.getString(i)
                }
            }
        }
        return result ?: uri.path?.substringAfterLast('/')
    }

    private fun importCredentials(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val raw = getFileName(uri) ?: "credentials.json"
            val fileName = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")   // নিরাপদ নাম (shell + path)
            try {
                val dir = File(filesDir, ".cloudflared").apply { mkdirs() }
                contentResolver.openInputStream(uri)?.use { input -> File(dir, fileName).outputStream().use { input.copyTo(it) } }
                withContext(Dispatchers.Main) {
                    etCredentialsRef?.setText(fileName)
                    postSystemLog("[SUCCESS] Imported credentials: $fileName")
                    toast("Credentials imported")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { postSystemLog("[ERROR] Import failed: ${e.message}") }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Backup / Restore  (.cloudflared + সব টানেল কনফিগ)
    // ═════════════════════════════════════════════════════════════════════

    private fun startBackup() {
        val checked = booleanArrayOf(false)
        MaterialAlertDialogBuilder(this)
            .setTitle("Backup configuration")
            .setMessage("The backup (.zip) contains your Cloudflare certificate & tunnel credentials and all tunnel settings. Keep it private.")
            .setMultiChoiceItems(arrayOf("Include Firebase auth keys"), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Choose location") { _, _ ->
                backupIncludeKeys = checked[0]
                backupLauncher.launch("cloudflared_backup_${System.currentTimeMillis() / 1000}.zip")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cfDir = File(filesDir, ".cloudflared")
                val json = tunnelManager.exportConfigJson(backupIncludeKeys)
                var files = 0
                contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out).use { zos ->
                        zos.putNextEntry(ZipEntry("tunnels.json"))
                        zos.write(json.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                        if (cfDir.exists()) {
                            cfDir.walkTopDown().filter { it.isFile }.forEach { f ->
                                zos.putNextEntry(ZipEntry("cloudflared/" + f.relativeTo(cfDir).path.replace('\\', '/')))
                                f.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                                files++
                            }
                        }
                    }
                } ?: throw IllegalStateException("Cannot open output file")
                withContext(Dispatchers.Main) {
                    postSystemLog("[SUCCESS] Backup saved ($files credential file(s) + tunnel settings)")
                    toast("Backup saved")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { postSystemLog("[ERROR] Backup failed: ${e.message}"); toast("Backup failed") }
            }
        }
    }

    private fun startRestore() = restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore backup")
            .setMessage("Credentials with the same name will be overwritten, and tunnel settings from the backup will be merged into your list. Running tunnels are not changed.")
            .setPositiveButton("Restore") { _, _ -> doRestore(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doRestore(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cfDir = File(filesDir, ".cloudflared").apply { mkdirs() }
                var files = 0
                var tunnels = 0
                contentResolver.openInputStream(uri)?.use { input -> 
                    val r = unzipBackup(input, cfDir)
                    files = r.first; tunnels = r.second
                } ?: throw IllegalStateException("Cannot open file")
                withContext(Dispatchers.Main) {
                    postSystemLog("[SUCCESS] Restored $files credential file(s) and $tunnels tunnel(s)")
                    toast("Restore complete")
                    refreshAll()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { postSystemLog("[ERROR] Restore failed: ${e.message}"); toast("Restore failed: ${e.message}") }
            }
        }
    }

    /** Zip-Slip ও zip-bomb প্রতিরোধসহ আনজিপ। @return (ফাইল সংখ্যা, ইমপোর্ট হওয়া টানেল) */
    private fun unzipBackup(input: InputStream, targetDir: File): Pair<Int, Int> {
        val root = targetDir.canonicalPath + File.separator
        var files = 0
        var tunnels = 0
        var total = 0L
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory) {
                    if (name == "tunnels.json") {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        tunnels = tunnelManager.importConfigJson(text)
                    } else {
                        val rel = name.removePrefix("cloudflared/")
                        val out = File(targetDir, rel).canonicalFile
                        if (!out.path.startsWith(root)) throw SecurityException("Unsafe path in backup: $name")
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > 50L * 1024 * 1024) throw SecurityException("Backup too large")
                                os.write(buf, 0, n)
                            }
                        }
                        files++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return files to tunnels
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Tunnel operations
    // ═════════════════════════════════════════════════════════════════════

    private fun startTunnel(instance: TunnelInstance) {
        if (!tunnelManager.isNetworkAvailable()) { showNetworkError(); return }
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
        tunnelManager.stopTunnel(instance.id)   // নন-ব্লকিং
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
        if (instance.tunnelUrl.isBlank()) { toast("No URL yet — tunnel is still starting"); return }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Tunnel URL", instance.tunnelUrl))
        toast("URL copied")
    }

    private fun showQr(instance: TunnelInstance) {
        if (instance.tunnelUrl.isBlank()) { toast("No URL yet — tunnel is still starting"); return }
        startActivity(Intent(this, QRCodeActivity::class.java).putExtra("tunnel_url", instance.tunnelUrl))
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UI refresh
    // ═════════════════════════════════════════════════════════════════════

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
            val mb = File(tunnelManager.getBinaryPath()).length() / 1_048_576
            binding.tvBinaryTitle.text = "cloudflared ready"
            binding.tvBinarySub.text = "Installed · $mb MB · arch ${BinaryDownloader.termuxArch()}"
            binding.btnInstall.text = "Update"
        } else {
            binding.tvBinaryTitle.text = "cloudflared not installed"
            binding.tvBinarySub.text = "Required to create tunnels (~30 MB)"
            binding.btnInstall.text = "Install"
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Permissions / battery / network
    // ═════════════════════════════════════════════════════════════════════

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
                } catch (e: Exception) { toast("Could not open battery settings") }
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

    // ═════════════════════════════════════════════════════════════════════
    //  Binary install / update
    // ═════════════════════════════════════════════════════════════════════

    private fun installBinary() {
        if (!tunnelManager.isNetworkAvailable()) { showNetworkError(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Install cloudflared")
            .setMessage("Downloads the latest cloudflared build from the Termux repository (~30–50 MB).")
            .setPositiveButton("Download") { _, _ -> performBinaryInstall(false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBinary() {
        if (!tunnelManager.isNetworkAvailable()) { showNetworkError(); return }
        val running = tunnelManager.getRunningInstances().size
        MaterialAlertDialogBuilder(this)
            .setTitle("Update cloudflared")
            .setMessage(if (running > 0) "This will stop $running running tunnel(s) and download the latest version." else "Download and install the latest cloudflared version?")
            .setPositiveButton("Update") { _, _ -> performBinaryInstall(true) }
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
            if (update) tunnelManager.updateBinary(onProgress, onComplete) else tunnelManager.installBinary(onProgress, onComplete)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Menu
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
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
                .setPositiveButton("Stop all") { _, _ -> tunnelManager.stopAllTunnels(); refreshAll() }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        R.id.action_share_logs -> {
            val logs = logAdapter.getAllLogs()
            if (logs.isBlank()) toast("No logs to share")
            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, logs)
                putExtra(Intent.EXTRA_SUBJECT, "Cloudflare Tunnel logs")
            }, "Share logs"))
            true
        }
        R.id.action_backup_cf -> { startBackup(); true }
        R.id.action_restore_cf -> { startRestore(); true }
        R.id.action_about -> {
            MaterialAlertDialogBuilder(this)
                .setTitle("Cloudflare v${BuildConfig.VERSION_NAME}")
                .setMessage(
                    "NDK: ${tunnelManager.nativeGetVersion()}\n" +
                    "Tunnels: ${tunnelManager.getRunningInstances().size}/${tunnelManager.getAllInstances().size} live\n" +
                    "cloudflared: ${if (tunnelManager.isBinaryInstalled()) "installed" else "not installed"}\n\n" +
                    "Developer: Abdullah Al Mamun\nDhaka, Bangladesh\nmamun995599@gmail.com\n+8801945120109\n\n" +
                    "Kotlin + NDK + Firebase REST"
                )
                .setPositiveButton("OK", null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        // কলব্যাক ছেড়ে দিই — singleton manager আর মৃত Activity ধরে রাখবে না
        tunnelManager.setGlobalLogCallback { }
        tunnelManager.setInstanceLogCallback { _, _ -> }
        tunnelManager.setTunnelUrlCallback { _, _ -> }
        tunnelManager.setStatusCallback { _, _ -> }
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
