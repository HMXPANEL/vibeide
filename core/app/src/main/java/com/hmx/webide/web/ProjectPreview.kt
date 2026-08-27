package com.hmx.webide.web

import com.hmx.webide.ai.context.WebProjectDetector
import com.hmx.webide.ai.context.WebProjectType
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Decides how to preview the currently opened web project and manages that preview's lifecycle.
 *
 * - Static HTML/CSS/JS -> serves the project directory over [WebPreviewServer].
 * - Vite / React / Node -> starts the project's dev/preview script (npm run dev|preview|start)
 *   and loads the URL it prints. Requires Node.js on the device; if absent a clear reason is
 *   returned instead of a fake page.
 *
 * Only one preview runs at a time; [stop] tears it down so background processes do not pile up.
 */
class ProjectPreview(private val projectDir: File) {

  enum class Kind { STATIC, DEV_SERVER, UNSUPPORTED }

  data class Result(
    val url: String?,
    val kind: Kind,
    val error: String?,
  )

  private var server: WebPreviewServer? = null
  private var process: Process? = null

  fun prepare(): Result {
    val type = WebProjectDetector.detect(projectDir)
    return when (type) {
      WebProjectType.STATIC_HTML -> startStatic()
      WebProjectType.UNKNOWN_WEB_PROJECT ->
        if (File(projectDir, "package.json").isFile) startDevServer()
        else Result(null, Kind.UNSUPPORTED,
          "Could not find an HTML entry file (index.html) in this project.")
      WebProjectType.VITE_PROJECT,
      WebProjectType.REACT_PROJECT,
      WebProjectType.NODE_PROJECT -> startDevServer()
    }
  }

  private fun startStatic(): Result {
    val s = WebPreviewServer(projectDir)
    val port = s.start()
    server = s
    return Result("http://127.0.0.1:$port/", Kind.STATIC, null)
  }

  private fun startDevServer(): Result {
    val node = findExecutable("node")
      ?: return Result(null, Kind.DEV_SERVER,
        "Could not start the dev server: Node.js was not found in this environment. " +
          "Vite/React projects require a dev server to run.")
    val npm = findExecutable("npm")
    val script = pickScript()
    val command = if (npm != null && script != null) "npm run $script" else null
    if (command == null) {
      return Result(null, Kind.DEV_SERVER,
        "package.json was found but the project type is not currently supported " +
          "(no dev/preview/start script).")
    }
    val process = try {
      ProcessBuilder("sh", "-c", command).directory(projectDir)
        .redirectErrorStream(true).start()
    } catch (e: Throwable) {
      return Result(null, Kind.DEV_SERVER,
        "Could not start the Vite/React preview process: ${e.message ?: e.javaClass.simpleName}")
    }
    this.process = process
    val url = waitForUrl(process)
    return if (url != null) Result(url, Kind.DEV_SERVER, null)
    else Result(null, Kind.DEV_SERVER,
      "Preview server started but did not become ready (no local URL detected).")
  }

  fun stop() {
    server?.stop()
    server = null
    process?.destroyForcibly()
    process = null
  }

  private fun pickScript(): String? {
    val pkg = File(projectDir, "package.json")
    if (!pkg.isFile) return null
    val text = runCatching { pkg.readText() }.getOrNull() ?: return null
    val body = Regex("\"scripts\"\\s*:\\s*\\{([^}]*)\\}").find(text)?.groupValues?.getOrNull(1) ?: return null
    val keys = Regex("\"(\\w+)\"\\s*:").findAll(body).map { it.groupValues[1] }.toSet()
    return when {
      "dev" in keys -> "dev"
      "preview" in keys -> "preview"
      "start" in keys -> "start"
      else -> null
    }
  }

  private fun waitForUrl(process: Process): String? {
    val reader = BufferedReader(InputStreamReader(process.inputStream))
    val found = AtomicReference<String?>()
    val t = thread(name = "preview-url-wait") {
      reader.forEachLine { line ->
        if (found.get() == null) {
          Regex("(https?://(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):\\d+)").find(line)?.let {
            found.set(it.groupValues[1])
          }
        }
      }
    }
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
      if (found.get() != null) break
      if (!process.isAlive) break
      Thread.sleep(400)
    }
    return found.get()
  }

  private fun findExecutable(name: String): String? {
    val p = runCatching {
      Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v $name")).let { proc ->
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out
      }
    }.getOrElse { return null }
    return p.takeIf { it.isNotBlank() }
  }
}
