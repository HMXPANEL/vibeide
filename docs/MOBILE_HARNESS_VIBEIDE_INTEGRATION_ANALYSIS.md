# Mobile-Harness × VibeIDE Integration Analysis

**Date:** 2026-09-09
**Status:** Analysis only — no code changes
**Scope:** Study Mobile-Harness for parts relevant to VibeIDE's web development runtime on Android

---

## 1. Executive Summary

Mobile-Harness is a production Android app (package `com.jarves.mh`, MIT license) that runs a full Ubuntu 20.04 ARM64 userspace inside PRoot on non-rooted Android devices. It bridges native Android (Jetpack Compose + Kotlin) to the PRoot Linux environment via a C++ JNI native library (`pocketspawn`), enabling process execution, stdout/stderr streaming, and process lifecycle management.

For VibeIDE (package `com.hmx.webide`), Mobile-Harness provides a **proven architectural blueprint** for running a real Node.js + npm + Vite development environment directly on Android. The key reusable components are:

1. **PRoot embedding and launching** — termux/proot fork compiled as `libproot.so`
2. **C++ JNI process bridge** (`pocketspawn`) — fork/execve with pipe-based stdout/stderr
3. **Runtime installation system** — download Ubuntu rootfs + Node.js bundles, verify checksums, staged extraction
4. **Foreground service pattern** — `RuntimeExecutionService` + `RuntimeSetupService` with wake locks
5. **Process lifecycle management** — process groups, PID-based kill, timeout, cleanup

**Recommendation: Option C — Hybrid approach.** Port the low-level C++ JNI bridge and PRoot embedding, and reimplement the higher-level Android orchestration (runtime management, project workspace, web preview) to match VibeIDE's existing architecture.

---

## 2. Mobile-Harness Architecture

### 2.1 Overview

```
Android Native Host (Kotlin + Jetpack Compose)
    ├── UI Layer (Projects • Chat • Terminal • Web Preview)
    ├── Foreground Runtime Service (Process Lifecycle & WakeLocks)
    ├── Android Keystore (AES-256 GCM Credentials)
    └── C++ JNI Process Bridge (pocketspawn) ── libpocketspawn.so
                                              ↓
Private Linux Subsystem (PRoot ARM64)
    ├── Ubuntu 20.04 LTS Rootfs (extracted to app-private storage)
    ├── Node.js 24.19.0 + npm 11.17.0
    ├── Git 2.25.1
    ├── Claude Code 2.1.261 ARM64
    ├── Project Workspace (/workspace)
    └── Toolchain Overlays (Python, Android, C++ — optional)
```

### 2.2 Key Technical Details

- **PRoot**: termux/proot fork (version 5.1.107.91), compiled as `libproot.so` and `libprootloader.so`
- **Process execution**: `fork()` + `execve()` inside PRoot via C++ JNI
- **Stdout/stderr**: Pipe-based — child process writes to a temp file, parent reads via `RandomAccessFile`
- **Process groups**: `setpgid(0, 0)` ensures `kill(-pid, signal)` stops the whole process tree
- **Runtime bundles**: zstd-compressed tar archives (`tar.zst`)
- **Architecture**: Exclusively ARM64 (`arm64-v8a`), API 26+
- **No root required**, but also not a hardened security boundary

### 2.3 Build System

- `build.gradle.kts` — Android application with Kotlin + Compose
- `CMakeLists.txt` — Compiles `libpocketspawn.so`, `libproot.so`, `libprootloader.so`, `libtalloc.so`, `libandroid-shmem.so`
- PRoot source: `third_party/proot` (termux/proot submodule)
- libandroid-shmem: `third_party/libandroid-shmem` (termux submodule)
- `executable_carrier.c` — AGG-compatible carrier that gets replaced with the real executable after linking

---

## 3. Relevant Source Files

### 3.1 Mobile-Harness Files and Their Purpose

| MH Path | Class/Module | Purpose | Can Reuse? | Should Reimplement? |
|---------|-------------|---------|------------|---------------------|
| `app/src/main/cpp/pocket_spawn.c` | `NativeSpawn` JNI | fork/execve/waitpid/kill via PRoot | **YES** (port C++ logic) | — |
| `app/src/main/cpp/pocket_launcher.c` | `pocket_launcher` | Clean exec wrapper, removes LD env | **YES** (reuse pattern) | — |
| `app/src/main/cpp/executable_carrier.c` | `executable_carrier` | AGG-compatible placeholder | Reference only | No |
| `app/src/main/cpp/CMakeLists.txt` | Build config | Compiles libpocketspawn, libproot | **YES** (adapt build) | — |
| `app/src/main/java/com/jarves/mh/runtime/NativeSpawnProcess.kt` | `NativeSpawnProcess` | Kotlin wrapper around C++ JNI | **YES** (port pattern) | — |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeExecutionService.kt` | `RuntimeExecutionService` | Foreground service, wake locks, notifications | Reference only | **Reimplement** |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeSetupService.kt` | `RuntimeSetupService` | Setup orchestration, state persistence | Reference only | **Reimplement** |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt` | `RuntimeInstaller` | Download, extract, verify rootfs + Node.js | **YES** (reuse patterns) | — |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeBridge.kt` | `RuntimeBridge` | Launch Claude Code in PRoot | Reference only | No (VibeIDE uses AI differently) |
| `app/src/main/java/com/jarves/mh/runtime/RuntimeLaunchConfigBuilder.kt` | `RuntimeLaunchConfigBuilder` | Build env vars for Claude Code | Reference only | No |
| `app/src/main/java/com/jarves/mh/data/ApiKeyVault.kt` | `ApiKeyVault` | Android Keystore AES-256 encryption | **YES** (reuse pattern) | — |
| `app/src/main/java/com/jarves/mh/data/AppPreferences.kt` | `AppPreferences` | SharedPreferences wrapper | Reference only | Reimplement |
| `app/src/main/java/com/jarves/mh/model/Models.kt` | Data models | Project, Chat, ToolRequest entities | Reference only | Reimplement |
| `app/src/main/assets/runtime-bundles/manifest.json` | Bundle manifest | Versioned runtime bundle metadata | **YES** (reuse pattern) | — |
| `.gitmodules` | Submodule config | termux/proot, termux/libandroid-shmem | **YES** (reuse submodules) | — |
| `LICENSE` | License | MIT | **YES** | — |

### 3.2 C++ JNI Bridge Details (`pocket_spawn.c`)

Key functions exposed via JNI:

- **`Java_com_jarves_mh_runtime_NativeSpawn_spawn(argv, env, cwd, output)`**: Creates pipe, `fork()`, `setpgid`, `dup2` pipe to stdout/stderr, `chdir(cwd)`, `execve(argv[0], argv, envp)`. Returns `int[] {pid, pipeWriteFd}`.
- **`Java_com_jarves_mh_runtime_NativeSpawn_waitFor(pid, noHang)`**: `waitpid(pid, &status, WNOHANG)`. Returns exit code or signal.
- **`Java_com_jarves_mh_runtime_NativeSpawn_kill(pid, signal)`**: `kill(-pid, signal)` targeting process group, fallback to `kill(pid, signal)`.

The `pocket_launcher.c` is a clean exec wrapper: removes `LD_LIBRARY_PATH` and `LD_PRELOAD`, then `execv()` the target binary. Used for launching binaries inside PRoot that might conflict with host linker settings.

### 3.3 RuntimeInstaller Patterns

Key patterns from `RuntimeInstaller.kt` (~62KB, the largest file):

1. **`isInstalled()`**: Checks for `libproot.so`, rootfs markers, node binary, claude binary, version markers
2. **`ensureInstalled(stacks, onProgress)`**: Downloads rootfs bundle, extracts, verifies, installs Node.js, sets up DNS resolver, creates Claude settings
3. **`installNodeIfNeeded(proot, from, to, onProgress)`**: Downloads Node.js ARM64 tar.gz from nodejs.org, verifies SHA-256, extracts to `/usr/local/lib/nodejs`, creates symlinks
4. **`process(proot, rootfs, workspace, env, guestCommand)`**: Builds the full `prout` command line with all flags, creates `NativeSpawnProcess`
5. **`runGuestCommand(proot, command, ...)`**: Executes a command inside PRoot, streams output, handles timeout, checks exit code
6. **`verifyGuest(proot, command, failureMessage)`**: Runs a verification command, waits, checks exit code
7. **`obtainRuntimeBundle(bundle, preferEmbedded, ...)`**: Loads from APK assets (offline) or downloads (online), verifies SHA-256
8. **`extractZstdTar(archive, destination)`**: Extracts `.tar.zst` using Apache Commons Compress
9. **`writeResolver()`**: Writes DNS nameservers to rootfs `/etc/resolv.conf`

### 3.4 `process()` Method — The Key Execution Entry

```kotlin
fun process(proot, rootfs, workspace, environment, guestCommand, guestWorkspacePath): Process {
    val args = listOf(
        proot.absolutePath,           // /data/data/com.jarves.mh/lib/libproot.so
        "--link2symlink", "-0", "-r", rootfs.absolutePath,
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-b", "/system", "-b", "/apex", "-b", "/vendor", "-b", "/product",
        "-b", "${workspace.absolutePath}:/workspace",
        "-b", "${bridge.absolutePath}:/pocket-bridge",
        "-w", guestWorkspacePath,
        ...guestCommand
    )
    return NativeSpawnProcess.start(
        argv = args,
        environment = buildMap {
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:...:/usr/bin:...")
            put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
            put("PROOT_NO_SECCOMP", "1")
            put("PROOT_TMP_DIR", ...)
            put("PROOT_LOADER", ...)
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            putAll(environment)
        },
        cwd = context.filesDir.absolutePath,
        outputFile = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
    )
}
```

---

## 4. VibeIDE Current Architecture

### 4.1 Existing Components (from prior phases)

```
Android Native Host (Kotlin + Jetpack Compose)
    ├── AI Chat (AIChatActivity, ChatEngine, ProviderStorage)
    ├── ToolRegistry + FileWriteGate + ProjectFileOps
    ├── ChatTaskStore + ChatHistoryStore
    ├── ProjectScanner + WebLanguageProvider + WebProjectType
    ├── ProjectPreview (static HTML → WebPreviewServer, Vite → startDevServer)
    ├── RuntimeManager (download/extract/verify Node.js)
    ├── TerminalEngine (shell command execution with timeout)
    └── IDEApplication (app initialization)
```

### 4.2 Current Runtime Strategy (Phase 3A+3B)

- `RuntimeManager`: Downloads Node.js Linux ARM64 tarball, extracts to `context.filesDir/node_runtime/`, sets up npm cache in `context.filesDir/npm_cache/`
- `TerminalEngine`: Uses `ProcessBuilder("sh", "-c", command)` — **no PRoot** — runs directly on Android
- `ProjectPreview.startDevServer()`: Uses `ProcessBuilder` with custom env mapping to RuntimeManager paths
- **Critical gap**: Linux ARM64 Node binary uses glibc; Android uses Bionic libc. Node works in PRoot-Distro (Termux) but **NOT** in native Android app execution.

---

## 5. Component-by-Component Comparison

### 5.1 Runtime

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| Linux userspace | termux/proot fork, Ubuntu 20.04 ARM64 | None | **Port** PRoot embedding |
| Node.js | Downloaded ARM64 tar.gz, extracted into rootfs | Downloaded ARM64 tar.gz, extracted to app-private dir | **Reuse** download/extraction pattern |
| npm | Bundled with Ubuntu rootfs | Separate npm download, symlinks | **Reuse** pattern |
| Runtime installation | `RuntimeInstaller` — staged, verified, progress | `RuntimeManager` — basic download/verify | **Reuse** patterns, reimplement |
| Architecture check | `Build.SUPPORTED_ABIS.contains("arm64-v8a")` | `Build.SUPPORTED_ABIS` detection | **Direct port** |
| Storage check | `StatFs` with 200MB minimum | `StatFs` with 200MB minimum | **Direct port** |

### 5.2 Process Execution

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| Process bridge | C++ JNI (`pocketspawn`), fork/execve inside PRoot | `ProcessBuilder("sh", "-c", cmd)` on Android | **Port** C++ JNI |
| Stdout/stderr | Pipe → temp file → `RandomAccessFile` | `ProcessBuilder` with `redirectErrorStream(true)` | **Reuse** pipe→file pattern |
| Process kill | `kill(-pid, signal)` (process group) | `process.destroyForcibly()` | **Reuse** group kill pattern |
| Timeout | `waitFor(timeoutMs)` with `WNOHANG` | `process.waitFor(timeoutMs, MILLISECONDS)` | **Reuse** pattern |
| Process groups | `setpgid(0, 0)` in child | Not used | **Port** for proper cleanup |
| Native exec wrapper | `pocket_launcher.c` — clean exec, removes LD env | Not used | **Port** for reliable binary launching |
| JNI bridge | `NativeSpawnProcess.kt` wraps `libpocketspawn.so` | Not used | **Port** entire bridge |

### 5.3 Foreground Service

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| Runtime setup service | `RuntimeSetupService` — foreground, wake lock, progress | Not present | **Reimplement** (VibeIDE needs setup UI) |
| Task execution service | `RuntimeExecutionService` — foreground, notifications, wake lock | Not present | **Reimplement** |
| Wake locks | `PowerManager.PARTIAL_WAKE_LOCK` (90min max) | Not present | **Port** pattern |
| Notification channels | 2 channels: runtime + task results | Not present | **Port** pattern |
| State persistence | `RuntimeSetupController` — JSON snapshot, survives process death | Not present | **Port** pattern |

### 5.4 Filesystem/Workspace

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| Project workspace | `/workspace` inside PRoot, bind-mounted from Android | `context.filesDir` + project-specific dirs | **Reimplement** (VibeIDE has own) |
| Workspace bind mount | `-b ${workspace.absolutePath}:/workspace` | N/A | **Port** pattern for PRoot |
| Runtime bridge dir | `/pocket-bridge` bind-mounted | N/A | Reference only |
| PRoot bind mounts | `/dev`, `/proc`, `/sys`, `/system`, `/apex`, `/vendor`, `/product` | N/A | **Port** all bind mounts |
| Path containment | `guestWorkspacePath` regex validation | `FileWriteGate` + `resolveInProject()` | **Keep** VibeIDE's |

### 5.5 Web Preview

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| Dev server | Vite/Next.js/Express inside PRoot | Vite inside PRoot (planned) | **Reuse** approach |
| WebView | Sandboxed WebView with console telemetry | `WebPreviewServer` + WebView | **Keep** VibeIDE's |
| Port management | Not detailed in source | `WebPreviewServer` port allocation | **Keep** VibeIDE's |
| Process lifetime | Foreground service manages lifecycle | `ProjectPreview` process field | **Reuse** service-based approach |
| Server startup detection | Regex on stdout for URL | `waitForUrl()` with regex | **Direct port** |
| Server shutdown | `RuntimeTaskController.requestStop()` → kill process group | `ProjectPreview.stop()` → destroyForcibly | **Reuse** group kill |

### 5.6 Global npm Cache

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| Cache strategy | Node.js is bundled in rootfs, no separate npm cache | `npm_config_cache` → `context.filesDir/npm_cache/` | **Keep** VibeIDE's approach |
| Shared cache | Not explicitly implemented | `RuntimeManager.npmEnv()` sets `npm_config_cache` | **Direct port** |
| Package overlay | Maven repository for Android, not npm | N/A | Not needed |

### 5.7 Security

| Area | Mobile-Harness | VibeIDE Current | Reuse/Reimplement |
|------|---------------|-----------------|-------------------|
| PRoot isolation | PRoot maps files/IDs in userspace, not hardened | Same limitation | **Acknowledge** |
| FileWriteGate | Not present in MH | Active — blocks protected paths | **Keep** VibeIDE's |
| Path containment | `guestWorkspacePath` regex validation | `resolveInProject()` | **Keep** VibeIDE's |
| Keystore encryption | `ApiKeyVault` — Android Keystore AES-256 GCM | Not present | **Port** pattern |
| Permission hook | `/opt/pocket/permission-hook.sh` for Claude | Not needed | No |
| `PROOT_NO_SECCOMP` | Set to "1" | Will need | **Port** |

---

## 6. Reuse vs Reimplement Matrix

| Component | Reuse Strategy | Rationale |
|-----------|---------------|-----------|
| **C++ JNI process bridge** | Direct port | `pocket_spawn.c` is generic — works with any PRoot setup. Compile as `libpocketspawn.so` |
| **PRoot embedding** | Direct port (submodule) | `third_party/proot` (termux/proot) is architecture-agnostic. Compile as `libproot.so` |
| **pocket_launcher.c** | Direct port | Generic clean exec wrapper |
| **RuntimeInstaller patterns** | Reuse patterns | Download/extract/verify/checksum logic is transferable |
| **NativeSpawnProcess.kt** | Port pattern | Kotlin JNI wrapper — adapt to VibeIDE package names |
| **RuntimeExecutionService** | Reimplement | Mobile-Harness specific to Claude Code tasks; VibeIDE needs web dev tasks |
| **RuntimeSetupService** | Reimplement | Mobile-Harness specific to onboarding; VibeIDE needs project creation flow |
| **FileWriteGate** | Keep as-is | Already in VibeIDE and works correctly |
| **ToolRegistry** | Keep as-is | Already in VibeIDE |
| **WebPreviewServer** | Keep as-is | Already in VibeIDE and handles static HTML preview |
| **TerminalEngine** | Rearchitect | Needs PRoot-backed process execution instead of raw ProcessBuilder |
| **RuntimeManager** | Rearchitect | Needs PRoot-aware Node.js installation |
| **ProjectPreview** | Extend | Needs PRoot-aware dev server execution |

---

## 7. License Analysis

### 7.1 Mobile-Harness License

- **License**: MIT (Copyright © 2026 Mobile Harness Contributors)
- **Third-party components**:
  - termux/proot: GPL-3.0+ (submodule) — this affects the compiled PRoot library
  - termux/libandroid-shmem: GPL-3.0+ (submodule)
  - Android Gradle Plugin: Apache 2.0
  - Node.js: MIT
  - Ubuntu rootfs: various (packages under their own licenses)
  - Claude Code CLI: Anthropic license (downloaded at runtime)
- **PRoot submodules**: The `third_party/proot` and `third_party/libandroid-shmem` are **GPL-3.0+**. This means the compiled `libproot.so` and `libandroid-shmem.so` are GPL-3.0+ derivatives.

### 7.2 Implications for VibeIDE

1. **MIT code can be copied freely** — the Kotlin/Java and C wrapper code under MIT license can be ported without issue
2. **PRoot (GPL-3.0+) creates a concern** — if `libproot.so` is statically linked into VibeIDE's APK, the entire APK could be considered a derivative work under GPL
3. **Dynamic linking** — loading `libproot.so` as a shared library (`.so`) at runtime is generally considered a separate work from the application that loads it. This is the **critical distinction**
4. **Recommended approach**: Link `libproocket.so` dynamically via `System.loadLibrary("pocketspawn")` — this avoids GPL contamination of VibeIDE's own code
5. **Attribution requirement**: The MIT license requires including the copyright notice and permission notice in all copies or substantial portions
6. **Third-party licenses**: Must compile `app/src/main/assets/licenses/` equivalent for VibeIDE

### 7.3 Action Items

- [ ] Add MIT copyright notice to VibeIDE's LICENSE or attribution file
- [ ] Add `assets/licenses/` directory with PRoot (GPL-3.0+) and other third-party licenses
- [ ] Ensure `libpocketspawn.so` is dynamically loaded, not statically linked
- [ ] Verify PRoot submodule licensing doesn't trigger GPL for the full APK
- [ ] Node.js runtime binaries remain under their own MIT license (separate from VibeIDE)

---

## 8. PRoot Security Considerations

### 8.1 PRoot Is NOT a Security Boundary

Mobile-Harness explicitly states: *"While isolated from other apps via standard Android sandbox permissions, PRoot is not a virtualization boundary or hardened jail."*

PRoot works by:
- `chroot`-like filesystem remapping (via `ptrace` and `personality` syscall)
- UID/GID mapping (mapped to the app's UID)
- `/proc` and `/sys` bind-mounting with filtered views
- No kernel-level isolation — no namespaces, no cgroups, no seccomp filtering by default

### 8.2 Security Implications for VibeIDE

1. **Shell commands inside PRoot still run as the VibeIDE app's UID** — same sandbox restrictions
2. **PRoot `PROOT_NO_SECCOMP=1`** disables seccomp filtering for the subprocess, which is necessary for glibc compatibility but removes a safety net
3. **Path traversal protection**: PRoot bind-mounts are explicit — only the paths listed in the `-b` flags are visible. VibeIDE's existing `FileWriteGate` still applies
4. **File system access**: The PRoot environment has access to bind-mounted paths. VibeIDE must ensure project directories are properly scoped
5. **No network isolation**: PRoot processes share the app's network stack

### 8.3 Interaction with VibeIDE's Safety System

| VibeIDE Safety Control | Interaction with PRoot | Recommendation |
|----------------------|----------------------|----------------|
| `FileWriteGate` | Still applies to real filesystem paths | **Keep as-is** — PRoot doesn't bypass real file permissions |
| Path containment | PRoot workspace is bind-mounted; paths outside workspace are not visible inside PRoot | **Keep as-is** |
| `ToolRegistry` validation | Tools dispatch before PRoot execution | **Keep as-is** |
| Terminal safety | Commands executed inside PRoot still respect VibeIDE's deny list | **Keep as-is** |
| `PROOT_NO_SECCOMP=1` | Required for glibc, but removes seccomp safety | **Document** as known tradeoff |

### 8.4 Critical Principle

> **Do NOT remove VibeIDE's safety system just because PRoot exists.**
>
> PRoot provides filesystem remapping, not security hardening. VibeIDE's `FileWriteGate`, path containment, and tool validation remain the primary defense layers.

---

## 9. Node/npm Runtime Strategy

### 9.1 Mobile-Harness Node.js Approach

1. **Node.js is bundled inside the Ubuntu rootfs** — extracted as part of the core bundle
2. **Also downloadable** — `RuntimeInstaller.installNodeIfNeeded()` downloads from `https://nodejs.org/dist/$VERSION/node-$VERSION-linux-arm64.tar.gz`, verifies SHA-256, extracts to `/usr/local/lib/nodejs` inside the rootfs, creates symlinks in `/usr/local/bin/`
3. **No separate npm cache** — npm uses the rootfs's own package cache
4. **Node.js version**: 24.19.0 (as of manifest.json)
5. **Architecture**: ARM64 Linux (glibc-based, runs inside PRoot)

### 9.2 VibeIDE Node.js Strategy

VibeIDE currently downloads the Node.js Linux ARM64 tarball directly to `context.filesDir/node_runtime/`. **This approach is fundamentally broken** because:

- The downloaded Node binary uses **glibc**, not Bionic
- `ProcessBuilder` runs it directly on Android → crashes immediately
- It only works inside PRoot (Termux-Distro)

### 9.3 Fixed Strategy: PRoot-Backed Node

**The solution**: Node.js must run inside the PRoot environment. This means:

1. Download the standard Node.js Linux ARM64 tarball (glibc is fine inside PRoot)
2. Extract into the PRoot rootfs (or a dedicated overlay directory)
3. Run Node.js via `prout -r <rootfs> -b <project>:/workspace <node_binary>`
4. The `pocket_launcher.c` pattern ensures clean exec without LD contamination

### 9.4 Recommended Architecture

```
VibeIDE RuntimeManager
    ↓ downloads
Node.js ARM64 tar.gz (from nodejs.org)
    ↓ extracts into
PRoot rootfs overlay (/usr/local/lib/nodejs)
    ↓ PRoot command line:
prout -r <rootfs> -b <project>:/workspace -b /dev -b /proc -b /sys \
      -b <runtime_dir>:/runtime -w /workspace \
      /usr/local/bin/node -v
    ↓ output captured via
pocketspawn pipe → temp file → RandomAccessFile
```

---

## 10. Global npm Cache Strategy

### 10.1 Mobile-Harness Approach

Mobile-Harness does **not** have a separate npm cache. npm packages are installed inside the PRoot rootfs's `/usr/local/lib/nodejs/node_modules` or in project directories. The rootfs itself is shared across all projects.

### 10.2 VibeIDE Global npm Cache Strategy

VibeIDE needs a **persistent, shared npm cache** across all projects:

```
context.filesDir/
├── node_runtime/          ← Node.js binary + libs
│   ├── bin/
│   │   ├── node           ← symlink to ../lib/nodejs/bin/node
│   │   ├── npm            ← symlink
│   │   └── npx            ← symlink
│   └── lib/
│       └── nodejs/        ← extracted Node.js
│           ├── bin/
│           └── lib/node_modules/
├── npm_cache/             ← Global npm cache
│   ├── _cacache/          ← Package cache
│   └── _logs/             ← npm logs
└── projects/              ← Individual projects
    └── my-react-app/
        ├── package.json
        ├── node_modules/  ← Project-specific
        └── ...
```

### 10.3 Implementation

```kotlin
// RuntimeManager.npmEnv() — VibeIDE already has this pattern
fun npmEnv(): Map<String, String> = mapOf(
    "npm_config_cache" to cachePath().absolutePath,
)

// In PRoot process, the cache dir must also be bind-mounted:
// -b ${cacheDir.absolutePath}:/root/.npm
```

The npm cache directory (`context.filesDir/npm_cache/`) persists across app restarts and is shared by all projects. Each project still has its own `node_modules/`.

---

## 11. Persistent Process Strategy

### 11.1 Mobile-Harness Pattern

- `NativeSpawnProcess` wraps a PID and output file
- `Process.waitFor()` delegates to `NativeSpawn.waitFor(pid, false)`
- `Process.destroy()` calls `NativeSpawn.kill(pid, 15)` (SIGTERM)
- `Process.destroyForcibly()` calls `NativeSpawn.kill(pid, 9)` (SIGKILL)
- Process groups (`setpgid`) enable `kill(-pid, signal)` to stop entire trees
- `RuntimeTaskController.requestStop()` provides a clean cancellation mechanism

### 11.2 VibeIDE Application

For VibeIDE, process persistence matters for:
1. **Long-running Vite dev servers** (`npm run dev` can run indefinitely)
2. **npm install** (can take 1-5 minutes)
3. **AI agent commands** (`run_command` tool)
4. **Preview server lifecycle** (start → keep alive → stop)

### 11.3 Critical Requirement: Process Groups

Vite dev servers spawn child processes. Without process groups, `kill(pid)` only kills the parent, leaving orphaned child processes. Mobile-Harness's `setpgid(0, 0)` pattern is **essential**.

---

## 12. Foreground Service Strategy

### 12.1 Mobile-Harness Pattern

Two services:
1. **`RuntimeExecutionService`**: Runs during AI agent tasks. Shows ongoing notification, acquires `PARTIAL_WAKE_LOCK` (max 90min), provides stop action.
2. **`RuntimeSetupService`**: Runs during initial setup. Shows progress notification, wake lock (max 45min).

Both use `START_REDELIVER_INTENT` and persist state via JSON snapshots.

### 12.2 VibeIDE Application

VibeIDE needs:
1. **Setup service**: Shows runtime download/extraction progress (similar to `RuntimeSetupService`)
2. **Execution service**: Manages long-running dev servers and AI task commands (similar to `RuntimeExecutionService`)
3. **Wake locks**: Essential for npm install and Vite server startup (Android kills background processes aggressively)
4. **Notification channels**: Required on Android O+ for foreground services

### 12.3 Key Parameters from Mobile-Harness

```kotlin
// Wake lock
PowerManager.PARTIAL_WAKE_LOCK
    "com.jarves.mh:active-coding-task"
    .apply { acquire(MAX_WAKE_LOCK_MS) } // 90 minutes

// Notification channels
"runtime" — Running coding tasks (IMPORTANCE_LOW, ongoing)
"task-results" — Task results (IMPORTANCE_DEFAULT, auto-cancel)
"runtime-setup" — Setup progress (IMPORTANCE_LOW, ongoing)
```

---

## 13. Vite/Web Preview Integration

### 13.1 Mobile-Harness Approach

Mobile-Harness provides a "Instant Web Preview" feature: *"Spun up a Vite, Next.js, or Express server? Test web interfaces in real-time within a restricted, sandboxed mobile WebView with console telemetry."*

The exact implementation details are not fully visible in the open-source files, but the architecture implies:
- Vite starts inside PRoot via `RuntimeExecutionService`
- Output is captured via the pipe
- URL is detected via regex on stdout
- WebView connects to `http://127.0.0.1:<port>/`

### 13.2 VibeIDE Current Approach

VibeIDE's `ProjectPreview.startDevServer()`:
1. Detects project type (Vite/React/Node)
2. Picks a script (`dev`, `preview`, `start`)
3. Starts `npm run <script>` via `ProcessBuilder`
4. Waits for URL via regex on stdout
5. Returns the URL to the WebView

### 13.3 Integration Plan

The current `ProjectPreview` approach needs to be updated to use PRoot-backed process execution:

```kotlin
// Current: ProcessBuilder("sh", "-c", command)
// Future: NativeSpawnProcess.start(
//     argv = listOf(prout, "-r", rootfs, "-b", project:/workspace, ..., "npm", "run", script),
//     environment = runtimeEnv,
//     cwd = projectDir,
//     outputFile = tempLogFile,
// )
```

The static HTML preview (`WebPreviewServer`) remains unchanged. The Vite dev server preview now goes through the PRoot bridge.

### 13.4 WebView Connection

- Vite dev server runs on a port inside PRoot
- PRoot forwards localhost connections (no port forwarding needed — PRoot shares the network)
- VibeIDE's WebView connects to `http://127.0.0.1:<port>/` on the device
- **No network bridge needed** — PRoot shares the host network namespace

---

## 14. Filesystem/Workspace Mapping

### 14.1 Mobile-Harness Workspace Binding

```kotlin
// In process() method:
val args = buildList {
    add(prout.absolutePath)
    add("--link2symlink")   // Follow symlinks in bind mounts
    add("-0")               // Null-terminated arguments
    add("-r", rootfs.absolutePath)  // Rootfs path
    add("-b", "/dev")       // Bind mount /dev
    add("-b", "/proc")      // Bind mount /proc
    add("-b", "/sys")       // Bind mount /sys
    // Android build tools (for Android projects)
    add("-b", "/system")
    add("-b", "/apex")
    add("-b", "/vendor")
    add("-b", "/product")
    // Project workspace bind mount
    add("-b", "${workspace.absolutePath}:/workspace")
    // Bridge directory (for JNI communication)
    add("-b", "${bridge.absolutePath}:/pocket-bridge")
    add("-w", guestWorkspacePath)  // Working directory
    addAll(guestCommand)
}
```

### 14.2 VibeIDE Workspace Mapping

VibeIDE needs to bind-mount the project directory into PRoot:

```kotlin
// VibeIDE project directory: context.filesDir/projects/<projectName>
// PRoot workspace path: /workspace

val args = listOf(
    runtimeManager.prootPath(),
    "--link2symlink", "-0", "-r", rootfsPath,
    "-b", "/dev", "-b", "/proc", "-b", "/sys",
    "-b", "${projectDir.absolutePath}:/workspace",
    "-w", "/workspace",
    "npm", "run", script
)
```

### 14.3 Runtime Bind Mounts

The Node.js runtime binary must be accessible inside PRoot:

```kotlin
// Bind-mount the runtime bin directory
add("-b", "${runtimeDir.absolutePath}:/runtime")
// Inside PRoot, /runtime/bin/node is accessible
```

Or alternatively, Node.js is extracted directly into the PRoot rootfs (`/usr/local/lib/nodejs`), which is cleaner.

---

## 15. Failure and Recovery Strategy

### 15.1 Mobile-Harness Recovery Patterns

1. **`RuntimeInstaller.isInstalled()`** — Checks multiple markers to determine if runtime is ready
2. **`RuntimeSetupController.restore()`** — Restores state from JSON snapshot after process death
3. **`RuntimeSetupController.fail()`** — Classifies errors (offline, dpkg-interrupted, generic) with friendly messages
4. **`RuntimeInstaller.repairLegacyMacosMetadata()`** — Cleans up `._*` AppleDouble files
5. **`RuntimeInstaller.migrateLegacyToolMarkers()`** — Upgrades from old bundle layout
6. **`RuntimeInstaller.cleanupLegacyWorkspaceScaffolding()`** — Removes stale alpha build artifacts
7. **`verifyGuest()`** — Runs verification commands with timeout
8. **`runGuestCommand()`** — Handles timeout, collects output, throws on non-zero exit

### 15.2 VibeIDE Application

VibeIDE already has some of these patterns:

1. **`RuntimeManager.isInstalled()`** — Checks `SETUP_MARKER` and `nodeBinary().isFile` ✅
2. **`TerminalEngine.execute()`** — Has timeout, error handling ✅
3. **`RuntimeManager.resumeInstall()`** — Resume interrupted download ✅
4. **Friendly error messages** — `friendlyError()` method ✅

VibeIDE needs to add:
- State persistence across app restarts (setup progress JSON)
- Process group cleanup on crash
- Legacy marker migration
- PRoot-specific error handling (`PROOT_NO_SECCOMP`, glibc issues)

---

## 16. Recommended VibeIDE Architecture

```
Android VibeIDE (com.hmx.webide)
    │
    ├── IDEApplication
    │       ├── RuntimeManager.init(context.filesDir)
    │       ├── RuntimeSetupService.start() [if first launch]
    │       └── RuntimeExecutionService.start() [when needed]
    │
    ├── UI Layer (Jetpack Compose)
    │       ├── AIChatActivity
    │       ├── ProjectPreview
    │       └── Settings/Setup Screen
    │
    ├── C++ JNI Bridge (libpocketspawn.so)
    │       ├── spawn(argv, env, cwd, outputFile) → int[] {pid, pipeFd}
    │       ├── waitFor(pid, noHang) → exitCode
    │       └── kill(pid, signal) → result
    │
    ├── NativeSpawnProcess (Kotlin wrapper)
    │       ├── start(argv, env, cwd, outputFile) → NativeSpawnProcess
    │       ├── waitFor() → exitCode
    │       ├── destroy() → SIGTERM to process group
    │       └── destroyForcibly() → SIGKILL to process group
    │
    ├── RuntimeInstaller (extends Mobile-Harness patterns)
    │       ├── isInstalled() → boolean
    │       ├── ensureInstalled() → InstalledRuntime
    │       ├── installNodeIfNeeded() → downloads ARM64 Node.js
    │       ├── process(proot, rootfs, workspace, env, command) → Process
    │       └── verifyGuest(proot, command) → boolean
    │
    ├── RuntimeManager (extends Mobile-Harness patterns)
    │       ├── init(filesDir)
    │       ├── isInstalled()
    │       ├── install() → downloads + extracts Node.js into PRoot
    │       ├── nodeBinary() → File
    │       ├── npmEnv() → Map
    │       └── runtimePathEnv() → String
    │
    ├── TerminalEngine (PRoot-backed)
    │       ├── execute(command, projectDir) → ToolResult
    │       └── builds PRoot command line
    │
    ├── ProjectPreview (PRoot-aware)
    │       ├── startDevServer() → starts npm run dev via PRoot
    │       └── stop() → kill process group
    │
    └── Safety Layer (existing VibeIDE)
            ├── FileWriteGate (unchanged)
            ├── ToolRegistry (unchanged)
            ├── ProjectFileOps (unchanged)
            └── TerminalSafety (unchanged)
```

### What Changes vs What Stays

| Layer | Action |
|-------|--------|
| **UI Layer** | **Reimplement** — VibeIDE's own Compose UI, not Mobile-Harness's |
| **AI Chat** | **Keep as-is** — VibeIDE's own chat system |
| **ToolRegistry + FileWriteGate** | **Keep as-is** — Already works correctly |
| **C++ JNI Bridge** | **Port** — `pocket_spawn.c` adapted to VibeIDE |
| **PRoot** | **Port** — termux/proot submodule |
| **RuntimeInstaller** | **Port patterns** — Adapt to VibeIDE's structure |
| **RuntimeManager** | **Rearchitect** — Add PRoot-aware execution |
| **TerminalEngine** | **Rearchitect** — PRoot-backed instead of raw ProcessBuilder |
| **ProjectPreview** | **Extend** — PRoot-aware dev server |
| **Services** | **Reimplement** — VibeIDE's own setup + execution services |

---

## 17. Exact Files to Port/Reimplement

### 17.1 Files to Port (C++ JNI Bridge)

| Source File | Destination | Notes |
|-------------|------------|-------|
| `pocket_spawn.c` | `app/src/main/cpp/pocket_spawn.c` | Direct port, adapt package name in JNI function signatures |
| `pocket_launcher.c` | `app/src/main/cpp/pocket_launcher.c` | Direct port |
| `executable_carrier.c` | `app/src/main/cpp/executable_carrier.c` | Direct port (AGG carrier pattern) |
| `CMakeLists.txt` | `app/src/main/cpp/CMakeLists.txt` | Adapt to VibeIDE build structure |
| `pocket_spawn.c` JNI | `NativeSpawnProcess.kt` | Port Kotlin wrapper class |
| `NativeSpawnProcess.kt` | `core/app/src/main/java/com/hmx/webide/ai/terminal/NativeSpawnProcess.kt` | Adapt to VibeIDE package |
| `RuntimeInstaller.kt` patterns | `core/app/src/main/java/com/hmx/webide/ai/terminal/RuntimeInstaller.kt` | Port download/extract/verify patterns |

### 17.2 Files to Reimplement (Android Orchestration)

| Component | Notes |
|-----------|-------|
| `RuntimeExecutionService` | VibeIDE-specific task management |
| `RuntimeSetupService` | VibeIDE-specific runtime setup flow |
| `RuntimeBridge` | VibeIDE uses different AI (not Claude Code) |
| UI screens | Completely VibeIDE's own |
| Data models | Completely VibeIDE's own |
| API key management | VibeIDE's own provider system |

### 17.3 Files NOT to Port

| Component | Reason |
|-----------|--------|
| `RuntimeBridge.kt` | Claude Code-specific; VibeIDE has its own AI chat |
| `RuntimeLaunchConfigBuilder.kt` | Claude Code-specific env vars |
| `LocalFormatGateway.kt` | Provider format gateway; VibeIDE uses different providers |
| `ClaudeRuntimeBridge.kt` | Claude Code-specific |
| All model/UI files | VibeIDE has its own data layer |
| `AndroidAppInstaller.kt` | APK installation; not needed for VibeIDE |
| `AndroidAppInstallReceiver.kt` | APK install receiver; not needed |

---

## 18. Files NOT to Port

### 18.1 Explicitly Excluded Components

1. **Claude Code integration** — VibeIDE uses its own AI provider system
2. **Android app building** — Mobile-Harness builds Android APKs; VibeIDE builds web apps
3. **Python/C++/PHP toolchains** — Out of scope for VibeIDE V1 (HTML/CSS/JS/TS/React/Vite only)
4. **Keystore encryption** — VibeIDE has different credential management
5. **Update system** — VibeIDE uses GitHub Actions for APK builds, not in-app updates
6. **F-Droid distribution** — Different distribution model
7. **Play Store compliance** — Different publishing path
8. **Claude Code CLI bundling** — Not relevant
9. **Maven repository** — Only needed for Android builds
10. **Composer/PHP** — Not in VibeIDE V1 scope

---

## 19. Proposed Implementation Phases

### Phase 1: Foundation (C++ JNI Bridge + PRoot)

**Goal**: Get `libpocketspawn.so` and `libproot.so` compiling and loading in VibeIDE.

- [ ] Add `third_party/proot` and `third_party/libandroid-shmem` as git submodules
- [ ] Create `app/src/main/cpp/CMakeLists.txt` for VibeIDE
- [ ] Port `pocket_spawn.c` with VibeIDE JNI package name
- [ ] Port `pocket_launcher.c`
- [ ] Create `NativeSpawnProcess.kt` Kotlin wrapper
- [ ] Verify `System.loadLibrary("pocketspawn")` loads on device
- [ ] **Test**: Basic `fork()` + `execve()` via PRoot
- **Risk**: PRoot compilation issues on Android NDK
- **Physical device test required**: Yes

### Phase 2: Runtime Installation in PRoot

**Goal**: Node.js + npm running inside PRoot, verified end-to-end.

- [ ] Extend `RuntimeInstaller` patterns to extract Node.js into PRoot rootfs
- [ ] Create `RuntimeManager.install()` that installs Node.js inside PRoot
- [ ] Build `prout` command line with all bind mounts
- [ ] Verify `node -v` and `npm -v` work inside PRoot
- [ ] Set up global npm cache (`npm_config_cache`)
- [ ] **Test**: `node -v`, `npm -v` via PRoot bridge
- **Risk**: glibc/Bionic compatibility issues with PRoot
- **Physical device test required**: Yes

### Phase 3: Terminal Engine PRot-Backed

**Goal**: `TerminalEngine` uses `NativeSpawnProcess` instead of `ProcessBuilder`.

- [ ] Replace `TerminalEngine.execute()` to build PRoot command line
- [ ] Add process group support (`setpgid`)
- [ ] Update `ProjectPreview.startDevServer()` to use PRoot-backed execution
- [ ] Update `ProjectPreview.stop()` to use process group kill
- [ ] **Test**: `npm install`, `npm run dev`, Vite server starts
- **Risk**: Process lifecycle management on Android
- **Physical device test required**: Yes

### Phase 4: Services and UI

**Goal**: Setup + execution foreground services with proper UX.

- [ ] Create `RuntimeSetupService` (VibeIDE version)
- [ ] Create `RuntimeExecutionService` (VibeIDE version)
- [ ] Add setup UI screen with progress indicators
- [ ] Add notification channels
- [ ] Add wake lock management
- [ ] Add state persistence (JSON snapshot)
- [ ] **Test**: Setup flow survives app restart
- **Risk**: Android battery optimization killing services
- **Physical device test required**: Yes

### Phase 5: Integration and Polish

**Goal**: Full VibeIDE runtime integration with AI tools.

- [ ] Wire `run_command` tool to PRoot-backed execution
- [ ] Add PRoot-specific error handling
- [ ] Add `PROOT_NO_SECCOMP`, `GLIBC_TUNABLES` environment
- [ ] Add license attribution files
- [ ] Add physical device validation checklist
- [ ] **Test**: Full end-to-end AI → npm install → Vite dev → WebView preview
- **Risk**: Complex integration issues
- **Physical device test required**: Yes

---

## 20. Risks / Unknowns

### 20.1 Technical Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| PRoot compilation fails on Android NDK | High | Use termux/proot submodule; test on API 28+ device |
| glibc binaries crash inside PRoot on Android | High | Verify with `node -v` test on physical device |
| `System.loadLibrary` fails due to ABI mismatch | Medium | Ensure `arm64-v8a` only; check `Build.SUPPORTED_ABIS` |
| Android kills foreground service | Medium | Use `PARTIAL_WAKE_LOCK` + `START_STICKY` + notification |
| PRoot process groups don't work correctly | Medium | Test `kill(-pid, SIGTERM)` pattern |
| `PROOT_NO_SECCOMP=1` causes security concerns | Low | Document as tradeoff; PRoot already not a security boundary |
| npm install is extremely slow under PRoot | Medium | Use global cache; consider parallel downloads |
| Vite dev server port conflicts inside PRoot | Low | Use unique ports per project; PRoot shares network namespace |

### 20.2 Unknowns Requiring Physical Device Testing

1. **PRoot + Node.js performance** — Is Node.js fast enough inside PRoot for development?
2. **Memory usage** — Ubuntu rootfs + Node.js + Vite = significant memory footprint
3. **Storage footprint** — Ubuntu rootfs is ~580MB uncompressed; Node.js adds ~50MB
4. **Battery impact** — Long-running dev servers with wake locks
5. **Android version compatibility** — PRoot behavior varies across Android versions
6. **Screen-on behavior** — Does `PARTIAL_WAKE_LOCK` keep the device awake enough?
7. **Background execution limits** — Android 12+ has strict background execution limits

### 20.3 Questions That Cannot Be Answered From Source Code

- **REQUIRES PHYSICAL DEVICE TEST**: Does `node -v` work inside PRoot on Android 9-14?
- **REQUIRES PHYSICAL DEVICE TEST**: Is npm install fast enough under PRoot?
- **REQUIRES PHYSICAL DEVICE TEST**: Does Vite dev server stay alive indefinitely?
- **REQUIRES PHYSICAL DEVICE TEST**: Does `kill(-pid, SIGTERM)` properly kill Vite and all children?
- **REQUIRES PHYSICAL DEVICE TEST**: Is the WebView able to connect to the Vite server?
- **REQUIRES PHYSICAL DEVICE TEST**: Does the runtime survive app restart?
- **REQUIRES PHYSICAL DEVICE TEST**: Is the PRoot compilation stable on the Android NDK version used?

---

## 21. Physical Android Validation Checklist

### Phase 1: Foundation

- [ ] Linux userspace starts (PRoot + Ubuntu rootfs extracts without error)
- [ ] PRoot starts (libproot.so loads via System.loadLibrary)
- [ ] Basic shell command works inside PRoot (`ls`, `cat`, `echo`)
- [ ] C++ JNI bridge loads (libpocketspawn.so loads without error)
- [ ] NativeSpawnProcess.spawn() returns valid PID
- [ ] NativeSpawnProcess.waitFor() returns exit code 0 for `echo hello`
- [ ] NativeSpawnProcess.kill() terminates process

### Phase 2: Runtime

- [ ] Node.js downloads (runtime bundle download completes)
- [ ] Node.js extracts (tar.zst extraction succeeds)
- [ ] `node -v` works inside PRoot → prints `v20.11.0` or similar
- [ ] `npm -v` works inside PRoot → prints npm version
- [ ] `npm install` works inside PRoot → installs packages without error
- [ ] Global npm cache works (packages cached in shared directory)
- [ ] Runtime survives app restart (isInstalled() returns true)
- [ ] Multiple projects can share runtime (same Node.js binary used)

### Phase 3: Terminal & Process

- [ ] Shell command works via TerminalEngine (`echo hello` returns stdout)
- [ ] Project directory is accessible inside PRoot (`cd /workspace && ls`)
- [ ] npm install works for a React/Vite project
- [ ] React/Vite project can install (`npm install` completes)
- [ ] `npm run dev` works → Vite dev server starts
- [ ] Vite server stays alive (process doesn't exit after 30s)
- [ ] stdout/stderr can be streamed (output captured in real-time)
- [ ] Process can be stopped (`kill` returns exit code 0)
- [ ] Process can be restarted (new npm run dev after stop)
- [ ] Process timeout/cancellation works (long-running command times out)
- [ ] Process group kill works (Vite + child processes all terminate)
- [ ] Runtime failure produces a useful error (node not found → clear message)

### Phase 4: Web Preview

- [ ] WebView can reach Vite (`http://127.0.0.1:<port>` loads React app)
- [ ] WebView shows correct content (CSS/JS/React rendering)
- [ ] WebView console telemetry works (errors logged)
- [ ] Vite HMR works (hot module replacement via WebSocket)
- [ ] Static HTML preview still works (WebPreviewServer unaffected)
- [ ] Vite preview works alongside static preview (both available)

### Phase 5: Safety & Recovery

- [ ] FileWriteGate still works (protected paths blocked)
- [ ] Path traversal is blocked (`../escape.txt` denied)
- [ ] Runtime setup state persists across app restart
- [ ] Setup can be cancelled and resumed
- [ ] Background execution survives battery optimization
- [ ] PRoot process cleanup on app crash (no orphan processes)
- [ ] License attribution is present in APK

---

## Final Decision

### Recommendation: **C — Hybrid: selectively port low-level components and reimplement the rest**

**Why not A (Directly port selected components)?**
Mobile-Harness's UI, data layer, and AI integration are deeply coupled to Claude Code. VibeIDE has its own AI chat system, tool registry, and project management. Porting the full runtime orchestration would require rewriting most of it anyway.

**Why not B (Reimplement everything)?**
The C++ JNI process bridge (`pocket_spawn.c`) and PRoot embedding are complex, well-tested, production-grade components. Reimplementing fork/execve with pipe-based stdout/stderr and process group management from scratch would take months and likely produce a less stable result. The termux/proot submodule is free to use.

**Why C (Hybrid)?**
The **C++ JNI bridge** and **PRoot embedding** are architecture-agnostic infrastructure that can be directly ported with minimal changes (just the package name and JNI signatures). The **Android orchestration** (setup service, execution service, UI, data models, runtime management) is application-specific and should be reimplemented to match VibeIDE's architecture.

**Exact components to port:**
1. `pocket_spawn.c` → `libpocketspawn.so` (C++ JNI bridge)
2. `pocket_launcher.c` → `libpocketlauncher.so` (clean exec wrapper)
3. `executable_carrier.c` → AGG-compatible carrier pattern
4. `CMakeLists.txt` → Adapted VibeIDE build configuration
5. `NativeSpawnProcess.kt` → Kotlin JNI wrapper (adapted)
6. `RuntimeInstaller.kt` → Download/extract/verify patterns (adapted)
7. `third_party/proot` (termux/proot submodule) → `libproot.so`
8. `third_party/libandroid-shmem` (termux submodule) → `libandroid-shmem.so`

**Exact components to reimplement:**
1. `RuntimeExecutionService` → VibeIDE task execution service
2. `RuntimeSetupService` → VibeIDE runtime setup service
3. `RuntimeBridge` → VibeIDE's own AI runtime bridge
4. `RuntimeLaunchConfigBuilder` → VibeIDE's own env var builder
5. All UI screens → VibeIDE's own Jetpack Compose UI
6. All data models → VibeIDE's own data layer
7. `TerminalEngine` → PRoot-backed execution (extends VibeIDE)
8. `ProjectPreview` → PRoot-aware dev server (extends VibeIDE)
9. `RuntimeManager` → PRoot-aware runtime (extends VibeIDE)

---

*End of analysis. No code changes made. Next step: Phase 1 implementation (C++ JNI Bridge + PRot compilation).*
