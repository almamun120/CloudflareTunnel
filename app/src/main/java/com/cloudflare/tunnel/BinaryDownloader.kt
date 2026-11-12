package com.cloudflare.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

class BinaryDownloader(private val context: Context) {

    companion object {
        private const val TAG = "BinaryDownloader"

        // Termux repository
        private const val TERMUX_REPO_URL = "https://packages.termux.dev/apt/termux-main/pool/main/c/cloudflared/"

        /** ডিভাইসের CPU অনুযায়ী Termux আর্কিটেকচার নাম (আগে সবসময় aarch64 ধরা হতো) */
        fun termuxArch(): String {
            for (abi in android.os.Build.SUPPORTED_ABIS) {
                when (abi) {
                    "arm64-v8a" -> return "aarch64"
                    "armeabi-v7a" -> return "arm"
                    "x86_64" -> return "x86_64"
                    "x86" -> return "i686"
                }
            }
            return "aarch64"
        }

        /** InputStream থেকে ঠিক [len] বাইট পড়ে (XZ/GZIP স্ট্রিম আংশিক রিড করে) */
        private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            return off
        }

        private fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            val tmp = ByteArray(8192)
            while (remaining > 0) {
                val n = input.read(tmp, 0, minOf(tmp.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
            }
        }
    }

    private var logCallback: ((String) -> Unit)? = null

    fun setLogCallback(cb: (String) -> Unit) {
        logCallback = cb
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logCallback?.invoke(msg)
    }

    /**
     * Download and install Termux cloudflared with XZ decompression support
     */
    suspend fun downloadAndInstall(
        onProgress: (Int, String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {

        val binaryFile = File(context.filesDir, "cloudflared")
        val tempDir = File(context.cacheDir, "download_temp")
        tempDir.mkdirs()

        try {
            log("[INFO] Fetching latest Termux version...")
            onProgress(0, "Detecting latest version...")

            // Get latest version from Termux repo
            val downloadUrl = getLatestTermuxUrl()

            if (downloadUrl == null) {
                log("[ERROR] Failed to detect latest version")
                run {
                    onComplete(false, "Failed to fetch version from Termux repository.\n\nCheck internet connection.")
                }
                return@withContext
            }

            log("[INFO] Download URL: $downloadUrl")
            onProgress(5, "Downloading .deb package...")

            // Download .deb file
            val debFile = downloadFile(downloadUrl, File(tempDir, "cloudflared.deb")) { progress, status ->
                onProgress(5 + progress / 2, status)
            }

            if (debFile == null || !debFile.exists()) {
                log("[ERROR] Download failed")
                run {
                    onComplete(false, "Download failed. Check internet connection.")
                }
                return@withContext
            }

            log("[SUCCESS] Downloaded: ${debFile.length() / 1_048_576} MB")
            onProgress(55, "Extracting binary...")

            // Extract binary from .deb
            val extractedBinary = extractBinaryFromDeb(debFile, tempDir) { progress ->
                onProgress(55 + progress / 3, "Extracting...")
            }

            if (extractedBinary == null || !extractedBinary.exists()) {
                log("[ERROR] Extraction failed")
                run {
                    onComplete(false, "Failed to extract binary from package")
                }
                return@withContext
            }

            log("[SUCCESS] Extracted binary: ${extractedBinary.length() / 1_048_576} MB")

            // Verify size
            if (extractedBinary.length() < 5_000_000L) {
                log("[ERROR] Binary too small: ${extractedBinary.length()} bytes")
                run {
                    onComplete(false, "Extracted binary is too small (corrupted)")
                }
                return@withContext
            }

            onProgress(95, "Installing...")

            // Install
            if (binaryFile.exists()) binaryFile.delete()
            extractedBinary.copyTo(binaryFile, overwrite = true)

            binaryFile.setExecutable(true, false)
            binaryFile.setReadable(true, false)
            binaryFile.setWritable(true, true)

            log("[SUCCESS] Installation complete: ${binaryFile.length() / 1_048_576} MB")
            onProgress(100, "Complete!")

            run {
                onComplete(true, "Installed Termux cloudflared (${binaryFile.length() / 1_048_576} MB)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            log("[ERROR] ${e.message}")
            e.printStackTrace()

            run {
                onComplete(false, "Error: ${e.message}")
            }
        } finally {
            try {
                tempDir.deleteRecursively()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Get latest Termux cloudflared URL by parsing repository index
     */
    private suspend fun getLatestTermuxUrl(): String? = withContext(Dispatchers.IO) {
        try {
            log("[INFO] Fetching Termux repository index...")

            val url = URL(TERMUX_REPO_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "CloudflareTunnelApp/1.0")
            connection.connect()

            if (connection.responseCode != 200) {
                log("[ERROR] HTTP ${connection.responseCode}")
                connection.disconnect()
                return@withContext null
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Find all aarch64.deb files
            val arch = termuxArch()
            val pattern = Pattern.compile("cloudflared_([0-9][0-9.]*(?:-[0-9]+)?)_" + arch + "\\.deb")
            val matcher = pattern.matcher(html)

            var latestVersion = ""
            while (matcher.find()) {
                val version = matcher.group(1)
                if (version != null && compareVersions(version, latestVersion) > 0) {
                    latestVersion = version
                }
            }

            if (latestVersion.isEmpty()) {
                log("[ERROR] No version found in repository")
                return@withContext null
            }

            val downloadUrl = "${TERMUX_REPO_URL}cloudflared_${latestVersion}_${arch}.deb"
            log("[SUCCESS] Latest version: $latestVersion")
            downloadUrl

        } catch (e: Exception) {
            log("[ERROR] Failed to parse repository: ${e.message}")
            null
        }
    }

    /**
     * Download file with progress
     */
    private suspend fun downloadFile(
        urlString: String,
        outputFile: File,
        onProgress: (Int, String) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        var connection: HttpURLConnection? = null

        try {
            log("[INFO] Downloading: $urlString")

            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "CloudflareTunnelApp/1.0")
            connection.connect()

            if (connection.responseCode != 200) {
                log("[ERROR] HTTP ${connection.responseCode}")
                return@withContext null
            }

            val fileSize = connection.contentLength.toLong()
            log("[INFO] File size: ${fileSize / 1_048_576} MB")

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    var lastProgress = 0

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        total += read

                        val progress = if (fileSize > 0) {
                            ((total * 100) / fileSize).toInt()
                        } else {
                            minOf(95, (total / 60_000).toInt())
                        }

                        if (progress != lastProgress && progress % 5 == 0) {
                            lastProgress = progress
                            val mb = total / 1_048_576
                            onProgress(progress, "Downloading... ${mb}MB")
                        }
                    }
                }
            }

            log("[SUCCESS] Downloaded to: ${outputFile.absolutePath}")
            outputFile

        } catch (e: Exception) {
            log("[ERROR] Download failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extract cloudflared binary from .deb package
     */
    private suspend fun extractBinaryFromDeb(
        debFile: File,
        outputDir: File,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        try {
            log("[INFO] Extracting .deb package...")

            // Parse .deb (AR archive)
            RandomAccessFile(debFile, "r").use { raf ->
                // Verify AR signature
                val signature = ByteArray(8)
                raf.read(signature)

                if (String(signature) != "!<arch>\n") {
                    log("[ERROR] Invalid .deb format")
                    return@withContext null
                }

                // Read AR members
                while (raf.filePointer < raf.length()) {
                    val header = ByteArray(60)
                    val read = raf.read(header)
                    if (read != 60) break

                    val fileName = String(header, 0, 16).trim { it <= ' ' || it == '/' }
                    val fileSizeStr = String(header, 48, 10).trim()
                    val fileSize = fileSizeStr.toLongOrNull() ?: continue

                    log("[DEBUG] AR member: $fileName ($fileSize bytes)")

                    if (fileName.startsWith("data.tar")) {
                        log("[INFO] Found data archive: $fileName")

                        // Extract data.tar.* to temp file
                        val dataTar = File(outputDir, fileName)
                        FileOutputStream(dataTar).use { out ->
                            val buffer = ByteArray(8192)
                            var remaining = fileSize

                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val actualRead = raf.read(buffer, 0, toRead)
                                if (actualRead == -1) break
                                out.write(buffer, 0, actualRead)
                                remaining -= actualRead
                            }
                        }

                        onProgress(50)

                        // Extract binary from tar
                        val binary = extractFromTar(dataTar, outputDir)
                        dataTar.delete() // Clean up

                        if (binary != null) {
                            log("[SUCCESS] Extracted cloudflared")
                            return@withContext binary
                        }
                    } else {
                        // Skip this member
                        raf.seek(raf.filePointer + fileSize)
                    }

                    // Align to even boundary
                    if (raf.filePointer % 2 != 0L) {
                        raf.seek(raf.filePointer + 1)
                    }
                }
            }

            log("[ERROR] cloudflared not found in .deb")
            null

        } catch (e: Exception) {
            log("[ERROR] Extraction failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Extract cloudflared from tar archive (supports .tar, .tar.gz, .tar.xz)
     */
    private fun extractFromTar(tarFile: File, outputDir: File): File? {
        return try {
            log("[INFO] Extracting TAR: ${tarFile.name}")

            // Open with appropriate decompression
            val inputStream = when {
                tarFile.name.endsWith(".tar.xz") -> {
                    log("[INFO] Using XZ decompression")
                    XZInputStream(FileInputStream(tarFile))
                }
                tarFile.name.endsWith(".tar.gz") -> {
                    log("[INFO] Using GZIP decompression")
                    GZIPInputStream(FileInputStream(tarFile))
                }
                else -> {
                    log("[INFO] Uncompressed TAR")
                    FileInputStream(tarFile)
                }
            }

            inputStream.use { input ->
                val buffer = ByteArray(512)

                while (true) {
                    // Read TAR header (512 bytes)
                    val headerRead = readFully(input, buffer, 512)
                    if (headerRead != 512) break

                    // Parse header
                    val fileName = String(buffer, 0, 100).trim { it <= ' ' || it == '\u0000' }
                    if (fileName.isEmpty()) break

                    // Parse file size (octal format)
                    val fileSizeStr = String(buffer, 124, 12).trim { it <= ' ' || it == '\u0000' }
                    val fileSize = try {
                        fileSizeStr.toLong(8)
                    } catch (e: Exception) {
                        0L
                    }

                    log("[DEBUG] TAR entry: $fileName ($fileSize bytes)")

                    // Check if this is cloudflared
                    if (fileName.endsWith("cloudflared") && !fileName.endsWith("/") && fileSize > 1_000_000L) {
                        log("[INFO] Found cloudflared, extracting...")

                        val outFile = File(outputDir, "cloudflared")
                        FileOutputStream(outFile).use { out ->
                            var remaining = fileSize
                            val fileBuffer = ByteArray(4096)

                            while (remaining > 0) {
                                val toRead = minOf(fileBuffer.size.toLong(), remaining).toInt()
                                val read = input.read(fileBuffer, 0, toRead)
                                if (read == -1) break
                                out.write(fileBuffer, 0, read)
                                remaining -= read
                            }
                        }

                        log("[SUCCESS] Extracted cloudflared: ${outFile.length()} bytes")
                        return outFile
                    } else {
                        // Skip file data
                        skipFully(input, fileSize)
                    }

                    // TAR blocks are 512-byte aligned
                    val padding = (512 - (fileSize % 512)) % 512
                    if (padding > 0) {
                        skipFully(input, padding)
                    }
                }
            }

            log("[WARN] cloudflared not found in TAR")
            null

        } catch (e: Exception) {
            log("[ERROR] TAR extraction failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Compare version strings (e.g., "2026.3.0" > "2025.12.1")
     */
    private fun compareVersions(v1: String, v2: String): Int {
        if (v2.isEmpty()) return 1

        val parts1 = v1.split(".", "-").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".", "-").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0

            if (p1 != p2) return p1.compareTo(p2)
        }

        return 0
    }
}