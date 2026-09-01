package com.hmx.webide.ai.terminal

import java.io.File

/**
 * VibeIDE-managed global runtime storage for downloaded web dependencies.
 *
 * Lives in app-private storage (never inside a user project) so packages can be
 * reused across projects while each project keeps its own [package.json] and
 * [node_modules]. Sub-directories are per package-manager; multiple versions of
 * the same package coexist naturally because the managers key by name@version.
 */
class PackageCache(private val root: File) {

  val dir: File = root.apply { mkdirs() }

  fun subdir(name: String): File = File(dir, name).apply { mkdirs() }

  // ponytail: V1 = npm only. pnpm/yarn/bun cache dirs are intentionally out of scope.
  /** Env vars that point every supported package manager at the shared cache. */
  fun cacheEnv(): Map<String, String> = mapOf(
    "npm_config_cache" to subdir("npm").absolutePath,
  )

  /** Deterministic cache path for a single name@version (used by tests / bookkeeping). */
  fun cachePathFor(name: String, version: String): File =
    File(subdir("packages"), "$name@$version")
}
