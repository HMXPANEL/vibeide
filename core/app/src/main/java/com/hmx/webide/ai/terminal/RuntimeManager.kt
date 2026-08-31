package com.hmx.webide.ai.terminal

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.hmx.webide.ai.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the VibeIDE Node.js runtime: download, extraction, verification, and paths.
 *
 * The runtime lives in app-private storage (never inside a user project).
 * All projects share the same Node binary and npm cache.
 *
 * Architecture: download on first React/Vite project creation → verify → mark complete.
 */
object RuntimeManager {

  private val log = LoggerFactory.getLogger(RuntimeManager::class.java)

  private const val RUNTIME_DIR_NAME = "node_runtime"
  private const val CACHE_DIR_NAME = "npm_cache"
  private const val SETUP_MARKER = ".setup_complete"
  private const val DOWNLOAD_TEMP = "node_download.tmp"
  private const val MIN_STORAGE_BYTES = 200L * 1024 * 1024 // 200MB safety margin
  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 30_000
  private const val NODE_VERSION = "20.11.0"

  // ponytail: configurable URL — swap this for an Android-compatible binary when available.
  // The Linux ARM64 binary works inside PRoot-Distro. For native Android app execution,
  // an Android/Bionic-linked binary is needed (see docs/PHASE_3_NODE_NPM_ARCHITECTURE_ANALYSIS.md).
  private var downloadUrl: String? = null

  private var runtimeDir: File? = null
  private var cacheDir: File? = null

  /**
   * Initialize with the app's private files directory.
   * Call once from IDEApplication or the setup flow.
   */
  fun init(appFilesDir: File) {
    runtimeDir = File(appFilesDir, RUNTIME_DIR_NAME).apply { mkdirs() }
    cacheDir = File(appFilesDir, CACHE_DIR_NAME).apply { mkdirs() }
    log.info("RuntimeManager initialized: runtime={}, cache={}", runtimeDir, cacheDir)
  }

  /**
   * Initialize with a Context (convenience overload).
   */
  fun init(context: Context) {
    init(context.filesDir)
  }

  /**
   * Set a custom download URL. Pass null to use the default.
   * Useful for testing with a local binary or a community Android build.
   */
  fun setDownloadUrl(url: String?) {
    downloadUrl = url
  }

  // ── Status checks ──────────────────────────────────────────────────

  /** Whether the runtime has been downloaded, extracted, and verified. */
  fun isInstalled(): Boolean {
    val dir = runtimeDir ?: return false
    return File(dir, SETUP_MARKER).isFile && nodeBinary().isFile
  }

  /** Whether the node binary exists and is executable. */
  fun isNodeExecutable(): Boolean {
    val node = nodeBinary()
    return node.isFile && (node.canExecute() || node.setExecutable(true))
  }

  // ── Paths ──────────────────────────────────────────────────────────

  /** Absolute path to the node binary. */
  fun nodeBinary(): File = File(runtimeDir, "bin/node")

  /** Absolute path to the npm CLI script. */
  fun npmBinary(): File = File(runtimeDir, "bin/npm")

  /** Absolute path to the npx CLI script. */
  fun npxBinary(): File = File(runtimeDir, "bin/npx")

  /** Absolute path to the runtime directory. */
  fun runtimePath(): File = runtimeDir ?: File(".")

  /** Absolute path to the global npm cache directory. */
  fun cachePath(): File = cacheDir ?: File(".")

  /** Environment variables to pass to every npm command. */
  fun npmEnv(): Map<String, String> = mapOf(
    "npm_config_cache" to cachePath().absolutePath,
  )

  /** Full PATH including the runtime bin directory. */
  fun runtimePathEnv(): String {
    val bin = File(runtimeDir, "bin").absolutePath
    val systemPath = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
    return "$bin:$systemPath"
  }

  // ── Download and install ───────────────────────────────────────────

  /**
   * Download, extract, and verify the Node.js runtime.
   * Must be called from a coroutine or background thread.
   * Returns [ToolResult.ok] on success or [ToolResult.error] on failure.
   */
  suspend fun install(): ToolResult = withContext(Dispatchers.IO) {
    try {
      val dir = runtimeDir
        ?: return@withContext ToolResult.error("NOT_INITIALIZED", "RuntimeManager not initialized. Call init() first.")

      if (isInstalled()) {
        return@withContext ToolResult.ok("Node.js runtime is already installed.")
      }

      // 1. Detect architecture
      val arch = detectArch()
      if (arch == null) {
        return@withContext ToolResult.error(
          "UNSUPPORTED_ARCH",
          "Your device architecture (${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}) is not supported. " +
            "VibeIDE requires an ARM64 (aarch64) device."
        )
      }

      // 2. Check storage
      val storageOk = checkStorage(dir)
      if (!storageOk) {
        return@withContext ToolResult.error(
          "INSUFFICIENT_STORAGE",
          "Not enough storage space. VibeIDE needs at least 200MB free to install the Node.js runtime. " +
            "Please free up some space and try again."
        )
      }

      // 3. Determine download URL
      val url = downloadUrl ?: buildDownloadUrl(arch)
        ?: return@withContext ToolResult.error(
          "NO_DOWNLOAD_URL",
          "Cannot determine the download URL for your device architecture ($arch). " +
            "Please set a custom download URL in settings."
        )

      log.info("Downloading Node.js from: {}", url)

      // 4. Download
      val tempFile = File(dir, DOWNLOAD_TEMP)
      downloadFile(url, tempFile)

      // 5. Extract
      log.info("Extracting Node.js runtime...")
      extractTarball(tempFile, dir)

      // 6. Make executable
      makeExecutable(File(dir, "bin/node"))
      makeExecutable(File(dir, "bin/npm"))
      makeExecutable(File(dir, "bin/npx"))

      // 7. Clean up download
      tempFile.delete()

      // 8. Verify
      log.info("Verifying Node.js installation...")
      val nodeResult = verifyNode()
      if (nodeResult.isError) {
        cleanup(dir)
        return@withContext nodeResult
      }

      val npmResult = verifyNpm()
      if (npmResult.isError) {
        cleanup(dir)
        return@withContext npmResult
      }

      // 9. Mark as installed
      File(dir, SETUP_MARKER).writeText(
        "installed_at=${System.currentTimeMillis()}\n" +
          "node_version=${nodeResult.message}\n" +
          "npm_version=${npmResult.message}\n" +
          "arch=$arch\n"
      )

      log.info("Node.js runtime installed successfully: {} / {}", nodeResult.message, npmResult.message)
      ToolResult.ok("Node.js ${nodeResult.message} and npm ${npmResult.message} installed successfully.")

    } catch (e: Exception) {
      log.error("Runtime installation failed", e)
      ToolResult.error("INSTALL_FAILED", friendlyError(e))
    }
  }

  /**
   * Resume an interrupted download. Call this on app launch if a partial download exists.
   */
  suspend fun resumeInstall(): ToolResult = withContext(Dispatchers.IO) {
    val dir = runtimeDir ?: return@withContext ToolResult.error("NOT_INITIALIZED", "RuntimeManager not initialized.")
    val tempFile = File(dir, DOWNLOAD_TEMP)

    if (!tempFile.isFile || tempFile.length() == 0L) {
      // No partial download — start fresh
      return@withContext install()
    }

    log.info("Resuming interrupted download ({} bytes already downloaded)", tempFile.length())
    install()
  }

  // ── Verification ───────────────────────────────────────────────────

  private fun verifyNode(): ToolResult {
    val node = nodeBinary()
    if (!node.isFile) {
      return ToolResult.error("NODE_NOT_FOUND", "Node binary not found at ${node.absolutePath}")
    }

    val result = execCommand(listOf(node.absolutePath, "-v"))
    val output = result.second.trim()

    return if (result.first == 0 && output.startsWith("v")) {
      ToolResult.ok(output)
    } else {
      ToolResult.error("NODE_VERIFY_FAILED",
        "Node.js verification failed. Output: $output (exit code: ${result.first})")
    }
  }

  private fun verifyNpm(): ToolResult {
    val npm = npmBinary()
    if (!npm.isFile) {
      return ToolResult.error("NPM_NOT_FOUND", "npm not found at ${npm.absolutePath}")
    }

    val result = execCommand(listOf(nodeBinary().absolutePath, npm.absolutePath, "-v"))
    val output = result.second.trim()

    return if (result.first == 0 && output.isNotEmpty()) {
      ToolResult.ok(output)
    } else {
      ToolResult.error("NPM_VERIFY_FAILED",
        "npm verification failed. Output: $output (exit code: ${result.first})")
    }
  }

  // ── Download ───────────────────────────────────────────────────────

  private fun downloadFile(urlStr: String, dest: File) {
    val url = URL(urlStr)
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = CONNECT_TIMEOUT_MS
    conn.readTimeout = READ_TIMEOUT_MS
    conn.setRequestProperty("User-Agent", "VibeIDE/1.0")

    // Support resuming interrupted downloads
    val existingBytes = if (dest.isFile) dest.length() else 0L
    if (existingBytes > 0) {
      conn.setRequestProperty("Range", "bytes=$existingBytes-")
      log.info("Resuming download from byte {}", existingBytes)
    }

    val responseCode = conn.responseCode
    if (responseCode != 200 && responseCode != 206) {
      conn.disconnect()
      throw RuntimeException("Download failed: HTTP $responseCode from $urlStr")
    }

    val totalSize = conn.contentLength.toLong() + existingBytes
    val inputStream = BufferedInputStream(conn.inputStream)
    val outputStream = FileOutputStream(dest, existingBytes > 0)

    try {
      val buffer = ByteArray(8192)
      var bytesRead: Int
      var totalRead = existingBytes
      while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        outputStream.write(buffer, 0, bytesRead)
        totalRead += bytesRead
        // Progress logging every 5MB
        if (totalRead % (5 * 1024 * 1024) < 8192) {
          val pct = if (totalSize > 0) (totalRead * 100 / totalSize) else 0
          log.info("Download progress: {}% ({} / {} bytes)", pct, totalRead, totalSize)
        }
      }
      log.info("Download complete: {} bytes", totalRead)
    } finally {
      outputStream.close()
      inputStream.close()
      conn.disconnect()
    }
  }

  // ── Extraction ─────────────────────────────────────────────────────

  private fun extractTarball(tarFile: File, destDir: File) {
    // The Node.js tarball contains a prefix directory (e.g., node-v20.11.0-linux-arm64/)
    // We extract to a temp dir, then move the bin/ contents to the runtime dir.

    val tempExtract = File(destDir, "temp_extract")
    tempExtract.mkdirs()

    try {
      // Use system tar if available (works in PRoot-Distro and most Linux environments)
      val result = execCommand(listOf(
        "tar", "xzf", tarFile.absolutePath,
        "-C", tempExtract.absolutePath,
        "--strip-components=1"
      ))

      if (result.first != 0) {
        throw RuntimeException("tar extraction failed: ${result.second}")
      }

      // Move bin/ to runtime dir
      val binDir = File(tempExtract, "bin")
      if (binDir.isDirectory) {
        val targetBin = File(destDir, "bin")
        targetBin.mkdirs()
        binDir.listFiles()?.forEach { file ->
          file.copyTo(File(targetBin, file.name), overwrite = true)
        }
      }

      // Move lib/ if it exists (npm needs it)
      val libDir = File(tempExtract, "lib")
      if (libDir.isDirectory) {
        val targetLib = File(destDir, "lib")
        targetLib.mkdirs()
        libDir.copyRecursively(targetLib, overwrite = true)
      }

      log.info("Extraction complete: bin/ and lib/ moved to runtime directory")

    } finally {
      tempExtract.deleteRecursively()
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────

  private fun makeExecutable(file: File) {
    if (file.isFile) {
      file.setExecutable(true, false)
      // Also try chmod as fallback
      execCommand(listOf("chmod", "755", file.absolutePath))
    }
  }

  private fun cleanup(dir: File) {
    log.warn("Cleaning up failed installation in {}", dir)
    File(dir, SETUP_MARKER).delete()
    File(dir, "bin").deleteRecursively()
    File(dir, "lib").deleteRecursively()
    File(dir, DOWNLOAD_TEMP).delete()
  }

  private fun execCommand(command: List<String>): Pair<Int, String> {
    return try {
      val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      Pair(exitCode, output)
    } catch (e: Exception) {
      Pair(-1, e.message ?: e.javaClass.simpleName)
    }
  }

  private fun checkStorage(dir: File): Boolean {
    return try {
      val stat = StatFs(dir.absolutePath)
      val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
      availableBytes >= MIN_STORAGE_BYTES
    } catch (e: Exception) {
      log.warn("Could not check storage: {}", e.message)
      true // Assume OK if we can't check
    }
  }

  private fun detectArch(): String? {
    val abis = Build.SUPPORTED_ABIS
    return when {
      abis.any { it.contains("arm64") || it.contains("aarch64") } -> "arm64"
      abis.any { it.contains("arm") } -> "arm32"
      else -> null // Unsupported
    }
  }

  private fun buildDownloadUrl(arch: String): String? {
    // ponytail: Linux ARM64 binary works in PRoot-Distro.
    // For native Android app execution, an Android/Bionic-linked binary is needed.
    // This URL is a starting point — swap for an Android-compatible build when available.
    return when (arch) {
      "arm64" -> "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.gz"
      "arm32" -> "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-armv7l.tar.gz"
      else -> null
    }
  }

  private fun friendlyError(e: Exception): String = when {
    e is java.net.UnknownHostException -> "No internet connection. Please check your network and try again."
    e is java.net.SocketTimeoutException -> "Download timed out. Please check your connection and try again."
    e is java.io.FileNotFoundException -> "Download file not found. The server may be unavailable."
    e.message?.contains("EACCES") == true -> "Permission denied. Please check app storage permissions."
    e.message?.contains("ENOSPC") == true -> "Storage full. Please free up space and try again."
    e.message?.contains("tar") == true -> "Failed to extract the runtime. The download may be corrupted. Please try again."
    else -> "Installation failed: ${e.message ?: e.javaClass.simpleName}"
  }
}
