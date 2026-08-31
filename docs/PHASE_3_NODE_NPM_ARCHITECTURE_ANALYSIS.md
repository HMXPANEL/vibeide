# Phase 3 Analysis — Download Runtime on First Use

This document analyzes how VibeIDE can support Node.js and npm on Android by downloading the runtime when the user first needs it, instead of bundling it inside the APK.

This is **analysis only**. No source files are modified, nothing is downloaded, nothing is installed, nothing is committed.

---

## A. What runtime/terminal system already exists

**Nothing.** There is no Node.js binary, no npm, and no terminal execution system in the current codebase. The only place Node is referenced is in `ProjectPreview.kt`, which tries to find `node` and `npm` on the system PATH using `command -v node`. On a stock Android device, this always returns null because Android does not include Node.js.

The Phase 3 skeleton files (uncommitted, never compiled in CI) define some types and safety rules, but they are dead code — nothing calls them.

---

## B. What Phase 3 work already exists and what is only a skeleton

### Real code (committed, APK-green):
- **`ProjectPreview.kt`** — can start a dev server via `ProcessBuilder` IF `node` and `npm` are on PATH. Waits up to 60 seconds for a localhost URL. Can kill the process. This is real, working code — it just needs a Node binary to point at.
- **`FileWriteGate.kt`** — already unblocks `package.json`, `vite.config.*`, `tsconfig*`, `tailwind.config.*`, `.npmrc`, `.editorconfig`, `.gitignore`. V1 config files are no longer blocked.
- **`WebProjectDetector.kt`** — detects Vite, React, Node, and static HTML projects from `package.json`.
- **`WebPreviewServer.kt`** — serves static files over localhost. Works for HTML/CSS/JS.

### Skeleton code (uncommitted, not compiled):
- **`TerminalState.kt`** — an enum (`RUNNING`, `COMPLETED`, `FAILED`, `STOPPED`) and two string constants. Not used anywhere.
- **`TerminalSafety.kt`** — a deny list for dangerous commands (`rm -rf /`, `mkfs`, `dd if=`, etc.). Not called by anything.
- **`PackageCache.kt`** — knows how to compute a cache directory path and set `npm_config_cache` env var. Not wired into any execution path.
- **`PackageManagerDetector.kt`** — can check if `npm` is on PATH via `command -v npm`. Not wired into any execution path.

### What is missing (does not exist at all):
- No Node.js binary anywhere.
- No terminal execution engine (no way to run shell commands from the AI).
- No `run_command` tool in the AI tool registry.
- No download mechanism for binaries.
- No setup/onboarding flow for runtime installation.
- No process lifecycle manager (no way to track/restart long-running processes).
- No foreground service for keeping a dev server alive in background.
- No integration between the AI loop and npm/terminal commands.

---

## C. How AI currently executes tools/commands

The AI tool loop works like this:

1. The user sends a message (e.g., "Create a React website").
2. `ChatEngine.runWithTools()` sends the message to the AI provider (OpenAI, Claude, Gemini).
3. The AI responds with tool calls (e.g., `write_file`, `read_file`, `list_files`).
4. Each tool call is executed via the `execute` callback — currently `ProjectFileOps.dispatch()`.
5. The result is fed back to the AI, which continues until it finishes or hits the 20-round limit.
6. Each tool call has a 30-second timeout.

**The only tools available are file tools:** `read_file`, `write_file`, `list_files`, `delete_file`. There is no tool for running shell commands, installing packages, or starting a dev server.

To support npm/terminal, a new `run_command` tool must be added to `ToolRegistry`, and the `execute` callback must be extended to route it to a terminal engine.

---

## D. Whether the current architecture can support an Android-local Node.js runtime

**Yes.** The architecture already supports it in principle:

- `ProjectPreview.kt` already runs shell commands via `ProcessBuilder("sh", "-c", command)`.
- `AiHttpClient.kt` uses `java.net.HttpURLConnection` for network requests — this same pattern can download a Node binary.
- The app has INTERNET permission (needed for AI provider calls).
- `context.filesDir` provides app-private storage where a Node binary can live.
- `Build.SUPPORTED_ABIS` tells us the device architecture (arm64-v8a, armeabi-v7a, etc.).
- Kotlin coroutines and `Dispatchers.IO` are available for background work.

The only thing missing is the actual Node binary and the code to download, extract, verify, and use it.

---

## E. Where a downloaded runtime could safely live inside the "HMX WEB IDE" storage

The "HMX WEB IDE" folder is at `/sdcard/HMX WEB IDE/` (external storage, user-visible). This is where **projects** live. The runtime should NOT live here — it is app-internal data, not user data.

### Recommended storage locations:

| What | Where | Why |
|------|-------|-----|
| Node/npm binaries | `context.filesDir/node_runtime/` | App-private, not user-visible, survives app updates |
| npm global cache | `context.filesDir/npm_cache/` | Shared across projects, app-private |
| Setup marker file | `context.filesDir/node_runtime/.setup_complete` | Signals that runtime is installed and verified |
| Download temp file | `context.filesDir/node_runtime/node_download.tmp` | For resumable downloads |
| Project node_modules | `project_dir/node_modules/` | Per-project, user-visible, npm default |

`context.filesDir` is typically `/data/user/0/com.hmx.webide/files/`. It is private to the app, survives app updates (unless data is cleared), and is writable without any special permissions.

---

## F. How the global npm cache could work

npm stores downloaded packages in a cache directory. By default this is `~/.npm` on desktop, but on Android it can be redirected using the `npm_config_cache` environment variable.

### How it works:

1. Before running any npm command, set the environment variable:
   ```
   npm_config_cache = /data/user/0/.../files/npm_cache/
   ```
2. npm reads/writes packages to this shared directory.
3. Multiple projects share the same cache — if Project A and Project B both need `react@18.2.0`, it is downloaded once and cached.
4. Each project still has its own `node_modules` (installed from the cache), but the cache avoids re-downloading.

### What `PackageCache.kt` already does:
- `cacheEnv()` returns `{"npm_config_cache": subdir("npm").absolutePath}` — this is exactly the env var npm needs.
- `cachePathFor(name, version)` returns a deterministic path for bookkeeping.

This skeleton is ready to be wired in — it just needs a `ProcessBuilder` to pass the env var.

### Cleanup:
- npm's cache can grow large. A simple cleanup strategy: remove cache entries older than 30 days, or cap total cache size at 500MB.
- `PackageCache.cachePathFor()` gives deterministic paths, so stale entries can be identified.

---

## G. How multiple projects could share the same runtime without sharing project-specific dependencies incorrectly

This is already the npm design:

- **Runtime (Node/npm):** One copy in `context.filesDir/node_runtime/`. All projects use the same binaries. No per-project copy.
- **npm cache:** One shared cache in `context.filesDir/npm_cache/`. Stores downloaded tarballs. Shared across projects. Does NOT contain installed code.
- **node_modules:** Each project has its own `node_modules/` directory. This is npm's default behavior — running `npm install` in Project A installs dependencies into `project_a/node_modules/`, not a shared location. Project B gets its own copy.

The separation is natural:
```
context.filesDir/
  node_runtime/          ← shared: node, npm binaries
  npm_cache/             ← shared: downloaded package tarballs
sdcard/HMX WEB IDE/
  project_a/
    package.json         ← project-specific
    node_modules/        ← project-specific (installed from shared cache)
  project_b/
    package.json         ← project-specific
    node_modules/        ← project-specific (installed from shared cache)
```

No special code is needed to enforce this — it is how npm already works.

---

## H. How Vite's development server could be started and stopped

`ProjectPreview.startDevServer()` already has this logic:

1. Find `node` and `npm` on PATH (currently fails because they don't exist).
2. Read `package.json` to find the `dev`, `preview`, or `start` script.
3. Run `npm run dev` via `ProcessBuilder("sh", "-c", "npm run dev").directory(projectDir)`.
4. Wait up to 60 seconds for the process to print `http://localhost:5173`.
5. Return the URL.

### What needs to change:
- Instead of `findExecutable("node")`, use the downloaded Node binary path directly.
- Pass `npm_config_cache` to the `ProcessBuilder` environment.
- Keep the existing `waitForUrl()` logic — it already works.
- Keep the existing `stop()` logic (`process?.destroyForcibly()`).

### What is missing:
- **Foreground service:** When the dev server is running, Android may kill it if the app goes to background. A foreground service with a persistent notification keeps it alive.
- **Process lifecycle management:** If the app is killed, the process dies. The next launch should detect this and offer to restart.
- **Multiple projects:** Only one dev server should run at a time. When the user switches projects, the old server must be stopped.

---

## I. How the existing Preview system could connect to a Vite dev server

`WebPreviewServer` serves static files over `http://127.0.0.1:<port>`. For Vite, the preview would load the Vite dev server URL instead.

### Current flow:
1. User opens Preview for a static HTML project.
2. `ProjectPreview.prepare()` detects `STATIC_HTML`.
3. `WebPreviewServer` starts on a random port.
4. The URL `http://127.0.0.1:<port>/` is returned.
5. The UI loads this URL in a WebView.

### Vite flow (after Node is available):
1. User opens Preview for a Vite/React project.
2. `ProjectPreview.prepare()` detects `VITE_PROJECT` or `REACT_PROJECT`.
3. `startDevServer()` finds Node, runs `npm run dev`, waits for URL.
4. The URL `http://localhost:5173` is returned.
5. The UI loads this URL in a WebView.

The WebView can load any URL — static or Vite. No changes to the WebView itself are needed. The only change is how the URL is obtained.

---

## J. Whether the Android version/device architecture creates any major limitations

### Architecture:
- **ARM64 (aarch64):** Most modern Android devices (2016+). The Node binary must be compiled for this architecture.
- **ARM32 (armeabi-v7a):** Some older or budget devices. V1 can document ARM64 as the minimum requirement. A fallback download for ARM32 can be added later.
- **x86/x86_64:** Emulators and rare devices. Not a priority for V1.

### Android version:
- **API 24+ (Android 7.0):** The app likely targets API 24+. Node.js binaries compiled for Android (API 24+) are available from the official Node.js project and community builds.
- **SELinux:** On stock Android, SELinux restricts what processes can do. However, executing a binary from the app's own private files directory (`context.filesDir`) is allowed — the app owns that directory.

### Storage:
- **Low-end devices (16GB–32GB total):** May have only 1–2GB free. A Node binary is ~30–50MB compressed, ~80–120MB extracted. `node_modules` can be 50–500MB per project. This is tight but workable for a dev tool.
- **Mid-range devices (64GB–128GB):** Plenty of space. No concern.
- **High-end devices (256GB+):** No concern.

### Network:
- `npm install` requires internet to download packages. This is unavoidable.
- The initial Node binary download also requires internet (one-time, ~30–50MB).
- No special network permissions beyond INTERNET (which the app already has for AI providers).

### Background processes:
- Android kills background processes aggressively. A foreground service is needed for long-running dev servers.
- Doze mode may delay network requests when the screen is off. Not a concern for interactive use.

---

## K. Whether downloading an Android-compatible Node runtime is technically realistic

**Yes.** Node.js provides official builds for Android:
- The Node.js website (`nodejs.org`) provides pre-built binaries for various platforms.
- Community builds (e.g., `termux/nodejs-bin-pkg`) provide ARM64 Node binaries that run on Android.
- The binary can be downloaded as a `.tar.gz` file, extracted to app-private storage, and executed via `Runtime.exec()`.

### Download URL pattern:
```
https://nodejs.org/dist/v20.11.0/node-v20.11.0-android-arm64.tar.gz
```
(or a community build URL if the official one doesn't provide Android binaries).

### What happens after download:
1. Extract the `.tar.gz` to `context.filesDir/node_runtime/`.
2. The extracted directory contains `bin/node` and `bin/npm`.
3. `chmod +x` the binaries.
4. Verify by running `node -v` and `npm -v`.
5. Write a `.setup_complete` marker file.

### Is this realistic?
- The download is ~30–50MB. On a decent connection (10 Mbps), this takes ~30 seconds.
- On a slow connection (1 Mbps), this takes ~5 minutes.
- The download should be resumable (using HTTP Range headers or tracking bytes downloaded).
- The user should see progress and be able to cancel.

---

## L. When should the runtime be downloaded?

### Option 1: On first launch
- **How:** Show a setup screen immediately after onboarding.
- **Pros:** Runtime is ready before the user does anything.
- **Cons:** Aggressive. The user may just want to explore the app or create a static HTML project. Forcing a 30–50MB download on first launch is bad UX.

### Option 2: When the user first creates a React/Vite project (RECOMMENDED)
- **How:** When the user taps "Create React App" or the AI detects a React/Vite project, check if the runtime exists. If not, show a setup dialog.
- **Pros:** The user clearly needs Node at this point. The download is justified and expected.
- **Cons:** Slight delay before the first React project works. But this is a one-time setup.

### Option 3: Through a separate settings/setup screen
- **How:** Add a "Web Runtime" section in Settings where the user can install/update/remove the runtime.
- **Pros:** Gives the user full control.
- **Cons:** Requires the user to know about it. Not discoverable for new users.

### Recommendation: Option 2 (with Option 3 as a supplement)

The primary trigger should be **when the user first creates or opens a React/Vite project**. This is the natural moment when Node is needed. A settings screen can also exist for power users who want to pre-install or manage the runtime.

The flow:
1. User taps "Create React App" (or AI detects a React project).
2. App checks: does `context.filesDir/node_runtime/.setup_complete` exist?
3. If yes → proceed normally.
4. If no → show a setup dialog: "VibeIDE needs a small runtime (~30MB) to run React projects. Download now?"
5. User taps "Download" → download with progress bar.
6. On completion → verify → write `.setup_complete` → proceed.

---

## M. What would happen in various scenarios

### Download is interrupted
- The download temp file (`node_download.tmp`) is kept.
- On next attempt, resume from where it left off (using HTTP Range header or file size check).
- The `.setup_complete` marker is NOT written, so the app knows setup is incomplete.

### Storage becomes full
- Before starting the download, check available storage with `StatFs`.
- If less than 200MB free, show an error: "Not enough storage. Free up space and try again."
- If storage fills up mid-download, the download fails and the partial file is deleted.

### Runtime is corrupted
- After download, verify the binary by running `node -v`.
- If `node -v` fails or returns an unexpected result, delete the runtime and retry.
- Optionally, check a SHA-256 hash of the downloaded file against a known-good hash.

### Network disappears
- The download fails with a network error.
- The partial file is kept for resumption.
- The user sees: "Network error. Download will resume when you're back online."
- On next app launch or next attempt, resume from where it left off.

### User changes project
- If a Vite dev server is running for Project A, and the user switches to Project B:
  - Stop the dev server for Project A.
  - Start a new dev server for Project B (if it needs one).
- The runtime is shared — no re-download needed.

### User opens another project
- Same as above. The runtime is global, not per-project.
- Each project uses the same Node binary but has its own `node_modules`.

### App is restarted
- The runtime persists in `context.filesDir` (survives app restarts).
- The dev server process dies with the app. On next launch, the app can detect this and offer to restart.
- The `.setup_complete` marker is still valid.

### App is updated
- `context.filesDir` persists across app updates (unless the user clears data).
- The runtime survives. No re-download needed.
- If the new app version requires a different Node version, a migration can be triggered.

### Runtime already exists
- The setup dialog is skipped.
- The app proceeds directly to the action (create project, run command, etc.).
- The runtime can be updated from the settings screen.

---

## N. Anything in the current VibeIDE architecture that would prevent this design

**Nothing major prevents this.** The architecture is already compatible:

1. `ProjectPreview.kt` can run shell commands via `ProcessBuilder` — it just needs a Node path.
2. `AiHttpClient.kt` can download files — it just needs a download function.
3. `ContextManager.kt` tracks the current project — the runtime can be project-agnostic.
4. `HmxFolder.kt` shows the per-project `.hmx/` pattern — the global runtime lives outside projects.
5. `Environment.java` defines storage paths — the runtime lives in `context.filesDir`.
6. `IDEApplication.kt` initializes services on launch — the runtime check can be added here.
7. `OnboardingActivity.kt` has a setup flow — a runtime setup step can be added.

The only architectural gap is that the AI tool loop (`ChatEngine.runWithTools()`) only routes to `ProjectFileOps.dispatch()` (file ops). A terminal execution path must be added. But this is an addition, not a change to existing code.

---

## O. What can be reused instead of rebuilding

| Existing system | Can be reused for | How |
|----------------|-------------------|-----|
| `ProjectPreview.startDevServer()` | Starting Vite dev server | Update to use downloaded Node path instead of `findExecutable("node")` |
| `ProjectPreview.waitForUrl()` | Detecting dev server URL | Already works, no changes needed |
| `ProjectPreview.stop()` | Stopping dev server | Already works, no changes needed |
| `AiHttpClient` pattern | Downloading Node binary | Same `HttpURLConnection` + retry logic |
| `PackageCache.cacheEnv()` | Pointing npm at shared cache | Returns the env var npm needs |
| `PackageManagerDetector` | Detecting npm availability | Check `command -v npm` in the downloaded runtime |
| `TerminalSafety.check()` | Blocking dangerous commands | Already has a deny list, just needs to be called |
| `TerminalState` | Reporting command status | Enum for RUNNING/COMPLETED/FAILED/STOPPED |
| `FileWriteGate` | Protecting project files | Already unblocks V1 config, blocks secrets |
| `WebProjectDetector` | Detecting project type | Already detects Vite/React/Node |
| `OnboardingActivity` | Runtime setup screen | Can add a runtime setup step |
| `IDEApplication` | Runtime initialization | Can check for runtime on launch |
| `Environment.java` | Storage paths | Already defines `PROJECTS_DIR` and `ROOT` |
| `HmxFolder` | Per-project structure | `.hmx/` pattern for project metadata |
| `ContextManager` | Current project tracking | Already tracks which project is open |
| `ToolRegistry` | Adding new tools | Add `run_command` tool definition |
| `ChatEngine.runWithTools()` | Executing terminal commands | Extend `execute` callback to route to terminal |
| `ProviderStorage` | Storing runtime version/config | Same `EncryptedSharedPreferences` pattern |
| `AiFactory` | Initializing runtime service | Same `init(context)` pattern |
| `MemoryService` | Per-project lifecycle | Same `withProject()`/`releaseProject()` pattern |

---

## P. Minimum new systems that would eventually be required

1. **`RuntimeManager`** — Singleton that manages the Node.js runtime:
   - Checks if runtime is installed (`.setup_complete` marker).
   - Provides `nodePath()` and `npmPath()`.
   - Downloads, extracts, and verifies the runtime.
   - Handles resumable downloads.
   - Reports installation status to the UI.

2. **`TerminalEngine`** — Executes shell commands in a project directory:
   - Runs a command via `ProcessBuilder` with the downloaded Node on PATH.
   - Captures stdout/stderr.
   - Enforces timeout.
   - Returns a `ToolResult` for the AI loop.
   - Manages process lifecycle (start, stop, restart).

3. **`run_command` tool** — Added to `ToolRegistry`:
   - Parameters: `command` (string), `workingDir` (string, optional).
   - Execution: routes to `TerminalEngine`.
   - Returns: stdout, stderr, exit code, whether it timed out.

4. **`RuntimeSetupDialog`** — UI for downloading the runtime:
   - Shows progress bar.
   - Handles cancel, retry, resume.
   - Shows errors clearly.
   - Writes `.setup_complete` on success.

5. **`DevServerService`** — Foreground service for long-running dev servers:
   - Keeps the Vite process alive when the app is in background.
   - Shows a persistent notification ("VibeIDE dev server running").
   - Stops the process when the user dismisses the notification.

---

## Q. Realistic implementation order for Phase 3

### Phase 3A — Runtime detection and download (foundation)
**Goal:** Download and verify a Node.js runtime on the device.

**What to build:**
- `RuntimeManager` singleton.
- Download function using `HttpURLConnection` (same pattern as `AiHttpClient`).
- Extract `.tar.gz` to `context.filesDir/node_runtime/`.
- `chmod +x` the binaries.
- Verify with `node -v` and `npm -v`.
- Write `.setup_complete` marker.
- Resume interrupted downloads.
- Check storage before download.
- Architecture detection via `Build.SUPPORTED_ABIS`.

**What already works:** `AiHttpClient` pattern for HTTP requests. `Environment.java` for storage paths.

**Risk:** The Node binary must actually run on the target device. Must test on physical hardware.

**Test:** Download Node, run `node -v`, verify output.

### Phase 3B — Terminal execution (AI tool)
**Goal:** Let the AI run shell commands in a project directory.

**What to build:**
- `TerminalEngine` class.
- `run_command` tool in `ToolRegistry`.
- Wire `execute` callback in `AIChatActivity` to route `run_command` to `TerminalEngine`.
- Pass `npm_config_cache` env var from `PackageCache.cacheEnv()`.
- Use `RuntimeManager.nodePath()` for the Node binary.
- Enforce timeout (30s per tool, already in `ChatEngine`).
- Wire `TerminalSafety.check()` before execution.

**What already works:** `TerminalSafety` deny list. `TerminalState` enum. `PackageCache.cacheEnv()`. `ChatEngine.runWithTools()` loop.

**Risk:** The AI may hallucinate wrong commands. Mitigation: validate `workingDir` is inside the project.

**Test:** Run `npm install` in a test project via the AI tool. Verify output.

### Phase 3C — npm integration
**Goal:** Let the AI install project dependencies and manage npm workflows.

**What to build:**
- Extend `run_command` to support `npm install`, `npm run dev`, `npm run build`.
- Parse npm output for errors.
- Report errors to the AI in a structured way.
- Wire `PackageManagerDetector.availableManagers()` to verify npm is available.

**What already works:** `PackageManagerDetector` detection logic. `PackageCache.cacheEnv()`. `run_command` from 3B.

**Risk:** `npm install` may take longer than 30s. Solutions: increase timeout for install, or let the AI poll.

**Test:** Create a React project, run `npm install`, verify `node_modules` is created.

### Phase 3D — Vite/React execution
**Goal:** Start the Vite dev server and detect the URL.

**What to build:**
- Update `ProjectPreview.startDevServer()` to use `RuntimeManager.nodePath()`.
- Pass `npm_config_cache` to `ProcessBuilder`.
- Keep existing `waitForUrl()` logic.
- Handle dev server failures (port in use, script missing).

**What already works:** `ProjectPreview.startDevServer()` structure. `waitForUrl()`. `stop()`. `pickScript()`.

**Risk:** Dev server may take longer than 60s to start on slow devices.

**Test:** Open a Vite/React project in Preview. Verify the URL loads.

### Phase 3E — Preview integration
**Goal:** Show the running project in the Preview screen.

**What to build:**
- Load dev server URL in WebView.
- Show status indicator (running/stopped/error).
- Handle project change (stop old, start new).
- Handle app background (foreground service).
- Handle app kill (restart on next launch).

**What already works:** `WebPreviewServer` for static files. WebView can load any URL. `ProjectPreview.stop()`.

**Risk:** Foreground service lifecycle on different Android versions.

**Test:** Open a React project in Preview. Verify the dev server URL loads. Switch projects. Verify old server stops.

### Phase 3F — Global cache
**Goal:** Share downloaded npm packages across projects.

**What to build:**
- Wire `PackageCache.cacheEnv()` into all npm command executions.
- Verify npm reads/writes from the shared cache.
- Add cache cleanup mechanism (age-based or size-based).
- Document cache location.

**What already works:** `PackageCache.cacheEnv()` returns the right env var. `PackageCache.cachePathFor()` for bookkeeping.

**Risk:** Cache corruption if multiple npm processes write simultaneously (unlikely for V1).

**Test:** Run `npm install` in two projects sharing a dependency. Verify second install is faster.

### Phase 3G — Settings and management
**Goal:** Let users manage the runtime from settings.

**What to build:**
- Settings screen section for "Web Runtime".
- Show current Node version, cache size, storage used.
- Update button (download newer Node version).
- Remove button (delete runtime and cache).
- Manual install option (for users who want to provide their own binary).

**What already works:** `ProviderStorage` pattern for storing settings. `EncryptedSharedPreferences`.

**Risk:** Low. This is a polish feature.

**Test:** Open settings, verify runtime info is displayed. Update/remove buttons work.

---

## What we already have

1. A working AI tool loop that can execute file operations and feed results back to the AI.
2. A preview system that can start a dev server IF Node.js exists on the device.
3. Project detection that knows whether a project is Vite, React, Node, or static HTML.
4. A file write gate that allows `package.json` and V1 config files while blocking secrets and out-of-scope framework configs.
5. Skeleton code for terminal safety, package cache, and package manager detection (not wired in, but the logic is correct).
6. An HTTP client pattern for network requests (used by AI providers).
7. An onboarding flow that can be extended with a runtime setup step.
8. Storage architecture that clearly separates app-private data from user projects.

---

## What is missing

1. A Node.js binary — nothing exists on the device.
2. A download mechanism — no code to download, extract, or verify a binary.
3. A terminal execution engine — no way to run shell commands from the AI.
4. A `run_command` tool in the AI registry.
5. Integration between the AI loop and npm/terminal commands.
6. A foreground service for long-running dev servers.
7. A setup UI for downloading the runtime.
8. A settings screen for managing the runtime.
9. A cache cleanup mechanism.
10. End-to-end testing of the full flow (AI creates project → npm install → npm run dev → preview).

---

## What can be reused

- `AiHttpClient` pattern for downloading.
- `PackageCache.cacheEnv()` for npm cache.
- `PackageManagerDetector` for npm detection.
- `TerminalSafety` for command blocking.
- `TerminalState` for status reporting.
- `ProjectPreview` for dev server lifecycle.
- `WebProjectDetector` for project type detection.
- `FileWriteGate` for write policy.
- `ToolRegistry` for adding new tools.
- `ChatEngine.runWithTools()` for the AI loop.
- `OnboardingActivity` for setup flow.
- `IDEApplication` for initialization.
- `Environment.java` for storage paths.
- `HmxFolder` for per-project structure.
- `ContextManager` for project tracking.
- `ProviderStorage` for settings storage.

---

## What needs to be built

1. `RuntimeManager` — download, extract, verify, and provide the Node.js runtime.
2. `TerminalEngine` — execute shell commands in a project directory.
3. `run_command` tool — AI-callable tool for running commands.
4. `RuntimeSetupDialog` — UI for downloading the runtime with progress.
5. `DevServerService` — foreground service for long-running dev servers.
6. Integration glue — wire everything together in `AIChatActivity`, `ProjectPreview`, and settings.

---

## Biggest technical risk

**The Node binary must actually run on the user's Android device.** This is the single biggest risk. If the binary does not execute (wrong architecture, SELinux restriction, Android version incompatibility), the entire Phase 3 roadmap is blocked.

**Mitigation:** Before any implementation, download a Node.js ARM64 binary for Android, put it on a physical device, and run `node -v`. If this works, everything else is straightforward. If it does not, the architecture must be revisited (different binary source, different runtime, or download fallback).

---

## My recommended architecture

**Download Node.js on first React/Vite project creation.** Store the runtime in `context.filesDir/node_runtime/`. Store the npm cache in `context.filesDir/npm_cache/`. Use `ProcessBuilder` with the downloaded Node binary for all commands. Use a foreground service for long-running dev servers. Extend the AI tool loop with a `run_command` tool. Reuse existing skeleton code where possible.

This is the best architecture because:
- **Small APK.** No Node binary bundled. The APK stays under 20MB for the web IDE features.
- **Works offline after setup.** Once downloaded, the runtime works without network.
- **Natural UX.** The user downloads the runtime when they clearly need it (first React project), not on first launch.
- **Shared globally.** All projects use the same Node binary and npm cache.
- **Resumable.** Interrupted downloads can be resumed.
- **Verifiable.** The app checks that the binary actually works before declaring setup complete.
- **Reusable.** Existing code (preview, safety, cache, detection) is mostly reused.

---

## Phase 3 step-by-step plan

1. **Verify Node on Android.** Download a Node.js ARM64 binary, run `node -v` on a physical device. This gates everything.
2. **Build `RuntimeManager`.** Download, extract, verify, provide paths. Add `.setup_complete` marker.
3. **Build `TerminalEngine`.** Execute commands via `ProcessBuilder`. Capture output. Enforce timeout.
4. **Add `run_command` tool.** Wire into `ToolRegistry`. Route through `TerminalEngine`.
5. **Wire AI loop.** Extend `execute` callback in `AIChatActivity` to handle `run_command`.
6. **Build `RuntimeSetupDialog`.** Show progress, handle cancel/resume, verify on completion.
7. **Update `ProjectPreview`.** Use `RuntimeManager.nodePath()` instead of `findExecutable("node")`.
8. **Test end-to-end.** AI creates React project → `npm install` → `npm run dev` → preview loads.
9. **Add foreground service.** Keep dev server alive in background.
10. **Add settings screen.** Manage runtime (update, remove, cache cleanup).

---

## What I should implement FIRST

**Verify that a Node.js ARM64 binary runs on a physical Android device.** Before writing any code, download a Node binary, push it to a device, `chmod +x`, and run `node -v`. If this works, proceed with the plan. If not, stop and investigate.

Everything else depends on this single test.
