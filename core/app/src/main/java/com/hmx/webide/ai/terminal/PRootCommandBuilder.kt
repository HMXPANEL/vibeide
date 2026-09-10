package com.hmx.webide.ai.terminal

import java.io.File

/**
 * Builds PRoot command lines for executing commands in the VibeIDE runtime.
 *
 * This is a minimal PRoot launcher infrastructure for Phase 1.
 * Full runtime integration (Node.js, npm, Vite) will be implemented in Phase 2.
 *
 * Ported from Mobile-Harness (https://github.com/techjarves/Mobile-Harness)
 * Original: RuntimeInstaller.process() method
 */
class PRootCommandBuilder(
    private val prootBinary: File,
    private val rootfs: File,
) {

    /**
     * Build a PRoot command to execute the given command inside the runtime.
     *
     * @param projectDir The project directory to bind-mount as /workspace
     * @param command The command and arguments to execute (e.g., ["sh", "-c", "echo hello"])
     * @param environment Additional environment variables
     * @param workingDir Working directory inside PRoot (default: /workspace)
     * @return List of command arguments for ProcessBuilder/NativeSpawnProcess
     */
    fun buildCommand(
        projectDir: File,
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        workingDir: String = "/workspace",
    ): List<String> {
        require(
            workingDir == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(workingDir),
        ) { "Invalid workspace path: $workingDir" }

        projectDir.mkdirs()
        File(rootfs, workingDir.removePrefix("/")).mkdirs()

        val bridgeDir = File(prootBinary.parentFile, "runtime-bridge").apply { mkdirs() }

        return buildList {
            add(prootBinary.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r", rootfs.absolutePath)

            // Essential system bind mounts
            add("-b", "/dev")
            add("-b", "/proc")
            add("-b", "/sys")

            // Project workspace bind mount
            add("-b", "${projectDir.absolutePath}:/workspace")

            // Bridge directory for potential future JNI communication
            add("-b", "${bridgeDir.absolutePath}:/vibeide-bridge")

            // Working directory
            add("-w", workingDir)

            // Command
            addAll(command)
        }
    }

    /**
     * Build the environment map for the PRoot process.
     *
     * This sets up the minimal environment needed for commands to run correctly
     * inside the PRoot environment.
     */
    fun buildEnvironment(
        contextFilesDir: File,
        additionalEnv: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val prootTemp = File(contextFilesDir, "proot-tmp").apply { mkdirs() }
        val nativeLibDir = File(contextFilesDir.parentFile, "lib").apply { mkdirs() }

        return buildMap {
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("LANG", "C.UTF-8")
            put("TERM", "xterm-256color")
            // PRoot-specific environment
            put("LD_LIBRARY_PATH", nativeLibDir.absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put("PROOT_TMP_DIR", prootTemp.absolutePath)
            put("PROOT_LOADER", File(nativeLibDir, "libprootloader.so").absolutePath)
            // glibc compatibility
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            putAll(additionalEnv)
        }
    }
}

/**
 * Result of a PRoot command execution.
 */
data class PRootExecutionResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)