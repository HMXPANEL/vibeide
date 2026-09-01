package com.hmx.webide.ai.terminal

import java.io.File

/**
 * Detects which package managers can actually run in the VibeIDE process sandbox
 * (Device/AndroidIDE environment), and which one a given project expects.
 *
 * We never assume npm is present — if a manager binary is missing we report it
 * clearly so the AI can return a real error instead of faking success.
 */
object PackageManagerDetector {

  // ponytail: V1 = npm only. pnpm/yarn/bun are intentionally out of scope.
  enum class Manager(val binary: String, val lockfile: String?) {
    NPM("npm", "package-lock.json"),
  }

  /** Binaries that exist on PATH inside the running shell. */
  fun availableManagers(shell: (String) -> Pair<Int, String>): List<Manager> {
    val (code, out) = shell("command -v ${Manager.NPM.binary} || true")
    return if (code == 0 && out.trim().isNotEmpty()) listOf(Manager.NPM) else emptyList()
  }

  /** Which manager a project expects. npm is the only supported V1 manager. */
  fun expectedManager(projectDir: File): Manager = Manager.NPM
}
