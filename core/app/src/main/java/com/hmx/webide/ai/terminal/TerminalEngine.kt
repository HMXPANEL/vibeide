package com.hmx.webide.ai.terminal

import com.hmx.webide.ai.tools.ToolResult
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Executes shell commands in a project directory using the VibeIDE Node.js runtime.
 *
 * Every command runs in the project directory with VibeIDE's runtime on PATH.
 * Commands are subject to [TerminalSafety] checks before execution.
 * Output (stdout + stderr) and exit code are captured and returned as [ToolResult].
 */
object TerminalEngine {

  private val log = LoggerFactory.getLogger(TerminalEngine::class.java)

  private const val DEFAULT_TIMEOUT_MS = 30_000L
  private const val LONG_TIMEOUT_MS = 120_000L // for npm install, etc.
  private const val MAX_OUTPUT_CHARS = 50_000 // prevent huge outputs from blowing up memory

  /** Commands that may need more time. */
  private val LONG_RUNNING = setOf("npm install", "npm ci", "npx", "npm run")

  /**
   * Execute a shell command in the given project directory.
   *
   * @param command The command to execute (e.g., "npm install", "node -v").
   * @param projectDir The project directory to run in.
   * @param timeoutMs Override timeout (0 = auto-detect based on command).
   * @return [ToolResult] with stdout/stderr and exit code.
   */
  fun execute(
    command: String,
    projectDir: File,
    timeoutMs: Long = 0L,
  ): ToolResult {
    // 1. Safety check
    val safetyCheck = TerminalSafety.check(command, projectDir)
    if (safetyCheck != null) return safetyCheck

    // 2. Validate project directory
    if (!projectDir.isDirectory) {
      return ToolResult.error("INVALID_PROJECT", "Project directory does not exist: ${projectDir.absolutePath}")
    }

    // 3. Check runtime is available
    if (!RuntimeManager.isInstalled()) {
      return ToolResult.error("RUNTIME_NOT_INSTALLED",
        "Node.js runtime is not installed. Please install it first (Settings → Web Runtime).")
    }

    // 4. Determine timeout
    val effectiveTimeout = if (timeoutMs > 0) timeoutMs else {
      if (LONG_RUNNING.any { command.trim().startsWith(it) }) LONG_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
    }

    // 5. Build environment
    val env = buildEnv()

    // 6. Execute
    log.info("Executing: {} in {} (timeout={}ms)", command, projectDir.absolutePath, effectiveTimeout)

    return try {
      val process = ProcessBuilder("sh", "-c", command)
        .directory(projectDir)
        .apply {
          environment().putAll(env)
        }
        .redirectErrorStream(true)
        .start()

      val output = StringBuilder()
      val reader = BufferedReader(InputStreamReader(process.inputStream))

      // Read output with timeout
      val completed = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)

      if (!completed) {
        // Timeout — kill the process
        process.destroyForcibly()
        val partial = reader.readText().take(MAX_OUTPUT_CHARS)
        return ToolResult.error("TIMEOUT",
          "Command timed out after ${effectiveTimeout / 1000}s: $command\n\nPartial output:\n$partial")
      }

      // Read remaining output
      val remaining = reader.readText()
      output.append(remaining)
      reader.close()

      val exitCode = process.exitValue()
      val trimmedOutput = output.toString().take(MAX_OUTPUT_CHARS)

      if (exitCode == 0) {
        log.info("Command succeeded (exit 0): {}", command.take(80))
        ToolResult.ok(trimmedOutput.ifEmpty { "(no output)" })
      } else {
        log.warn("Command failed (exit {}): {}", exitCode, command.take(80))
        ToolResult.error("COMMAND_FAILED",
          "Command failed with exit code $exitCode: $command\n\n$trimmedOutput")
      }
    } catch (e: Exception) {
      log.error("Command execution error: {}", command, e)
      ToolResult.error("EXECUTION_ERROR",
        "Could not execute command: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  /**
   * Execute a command and return raw output (for internal use, e.g., verification).
   * Returns Pair<exitCode, output>.
   */
  fun executeRaw(command: List<String>, workDir: File? = null): Pair<Int, String> {
    return try {
      val pb = ProcessBuilder(command)
        .redirectErrorStream(true)
      if (workDir != null) pb.directory(workDir)

      // Add runtime to PATH
      val env = pb.environment()
      env["PATH"] = RuntimeManager.runtimePathEnv()
      env.putAll(RuntimeManager.npmEnv())

      val process = pb.start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor(10, TimeUnit.SECONDS)
      Pair(if (exitCode) process.exitValue() else -1, output)
    } catch (e: Exception) {
      Pair(-1, e.message ?: e.javaClass.simpleName)
    }
  }

  /**
   * Build the environment variables for command execution.
   * Ensures VibeIDE's Node runtime is used, not any system Node.
   * Prepends the runtime bin dir to PATH so our node/npm are found first,
   * but keeps system tools (sh, tar, chmod) accessible.
   */
  private fun buildEnv(): Map<String, String> {
    val env = mutableMapOf<String, String>()

    // Prepend VibeIDE's runtime bin to PATH (system PATH remains accessible)
    val systemPath = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
    val runtimeBin = File(RuntimeManager.runtimePath(), "bin").absolutePath
    env["PATH"] = "$runtimeBin:$systemPath"

    // Set npm cache to VibeIDE's shared cache
    env.putAll(RuntimeManager.npmEnv())

    // Set HOME to avoid npm reading user's home .npmrc
    env["HOME"] = RuntimeManager.runtimePath().absolutePath

    // Ensure NODE points to our binary
    env["NODE"] = RuntimeManager.nodeBinary().absolutePath

    return env
  }
}
