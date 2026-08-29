# VibeIDE Analysis — AI Reliability/Safety (Phase 1) & File-Tool/Agent-Tool System (Phase 2)

> Analysis-only document. No source files were modified while producing this report.
> Reference: `deepseek-harness-master/` (read-only comparison only; nothing copied due to license — Harness is MIT, VibeIDE is GPL-3.0).

## Scope note
This report distinguishes four states:
- **Code exists** — the logic is written.
- **Connected** — it is actually wired into the request flow.
- **Used** — it triggers under real build/plan conditions.
- **Works** — it behaves correctly (some items need real-device testing to fully confirm).

---

## 1. WHAT IS ALREADY WORKING

Real, connected, and used — not just code that exists:

- **Build/Plan actually use file tools.** `AIChatActivity.runTask` sends the request through `ChatEngine.runWithTools` when mode is build/plan and the provider supports tools (`OpenAiProvider` advertises `tools`). The AI does not write files by typing text — the app does it. (`AIChatActivity.kt` `runTask`, `ChatEngine.kt` `runWithTools`)
- **Multi-file + multi-tool works and is sequential.** `runWithTools` loops: model returns tool calls -> app runs each one and waits for the result -> feeds results back -> repeats. The AI sees file A's result before editing file B, and can `read_file` after `write_file` to verify. (`ChatEngine.kt` loop, `ProjectFileOps.dispatch`)
- **One failed tool does NOT kill the task.** `ProjectFileOps.dispatch` wraps everything in `runCatching`; a failure becomes an error *string* returned to the model, and the loop continues. The AI can self-correct. (`ProjectFileOps.kt:20-29`)
- **Path safety is solid.** Every AI file op goes through `ProjectFileOps.resolve`, which rejects absolute paths, `..`, and uses the *canonical* (symlink-resolved) path to confirm the file stays inside the project. A symlink pointing outside is caught. (`ProjectFileOps.kt:63-74`)
- **Provider errors are well-classified.** `ProviderErrorMapper` reads the real error body and splits 402/429/401/403/404/400/5xx into distinct types (quota, rate-limit, auth, model-not-found, bad-request, server-error). The user sees a friendly message, not a raw dump. (`ProviderErrorMapper.kt`, `AIChatActivity.friendlyError`)
- **No duplicate requests.** `sendMessage` refuses a new send while a task is RUNNING/WORKING. On failure, `ChatEngine` removes the user message from history, so a retry never double-sends. (`AIChatActivity.kt` send guard, `ChatEngine` remove-on-throw)
- **Task survives app death.** Task state is saved to `<project>/.androidide/chat_task.json` on every chunk; a killed task becomes INTERRUPTED and shows Continue. (`ChatTaskStore.kt`)

---

## 2. WHAT IS PARTIALLY WORKING

- **Retry is inconsistent.** Network failures are auto-retried up to 2x at the HTTP layer (`AiHttpClient.retryOnTransientError`). But **Build/Plan have zero retry** — they call `runWithTools` directly. Only **Chat mode** retries a 429 once (with `Retry-After`, capped at 60s). So a momentary rate-limit or network blip fails an entire Build task, while a Chat message survives.
- **Tool results are plain text, not typed.** The AI gets a string like `"Error: file not found: x"`. It works, but there is no structured `isError`/`code` like DeepSeek's `ToolExecutionResult`, so the AI must parse English to decide retry-vs-stop.
- **"Continue" is a re-run, not a true resume.** `continueTask` re-sends the *original prompt* from scratch. It does not preserve mid-task tool results or partial progress. Fine for many cases, but it is not resuming from the exact interrupted state. (Code exists & connected; correctness of "resume" cannot be verified without a device.)
- **Two write paths, both safe but not unified.** Tools go through `ProjectFileOps`; the legacy `[[WRITE:]]` fallback goes through `applyEdits` (its own canonical check). Both check paths, so neither escapes the project — but there is no single write gate.

---

## 3. WHAT IS COMPLETELY MISSING

- **Per-tool timeout.** There is only a 10-minute *whole-request* HTTP read timeout (`OpenAiProvider` sets `readTimeout = 600_000`). Individual tool calls (and any future terminal/long command) have **no timeout of their own**. A hung tool would block forever.
- **Write-intent / permission policy.** Anything inside the project can be overwritten or deleted, including `package.json`, configs, or the user's own source. `delete_file` can delete *any* project file (it refuses directories, but not important files). No "dangerous file" protection.
- **Structured tool errors.** No `isError` flag or error `code` returned to the model; just text.
- **Central tool registry + tool metadata.** Tools are a static Kotlin list in `AIChatActivity` plus a `when()` in `ProjectFileOps`. No place to declare a tool's timeout, read-only/dangerous status, or permissions. Adding a terminal/preview/git/MCP tool means editing two spots by hand.
- **Persistent terminal / long-running background jobs.** VibeIDE can run `npm` only inside `ProjectPreview` for preview; there is no general, observable shell the AI can drive. (Confirmed: no terminal capability found.)
- **Auto-fix / self-heal loop.** On failure the task just stops and shows Continue; the AI is not automatically asked to retry/fix.

---

## 4. WHAT IS CURRENTLY BROKEN

**No crash-level bug was found from tracing.** The system runs. The problems above are *gaps* (missing protections), not broken behavior. Two latent risks (not yet "broken"):

- **No max iteration guard on the tool loop.** `runWithTools` loops as long as the model returns tool calls. A model that loops could run unbounded (no task-level cap). Not a crash today, but a real safety gap.
- **Build/Plan 429/network = hard fail.** As noted, no retry there. This is the most likely "feels broken" symptom a user will hit (a Build randomly fails and only Continue recovers it).

> Cannot verify without real-device testing: the *visual* chat/preview behavior, whether `Continue` restores a usable state on a real interruption, and whether provider streaming behaves identically on-device. The logic is connected; the UX is unverified here.

---

## DEEPSEEK HARNESS COMPARISON (reference only, no code copied)

- **tool-call timeout guard** -> **MISSING**. VibeIDE has whole-request timeout only; Harness has a per-tool `TOOL_TIMEOUT` (`guard/timeout-policy`).
- **writable roots / path containment** -> **PARTIALLY EXISTS**. `ProjectFileOps` canonical check is the same *idea* as Harness `sandbox/roots.ts`, but VibeIDE has no allow-list policy or config-file protection.
- **filesystem service** -> **PARTIALLY EXISTS**. `ProjectFileOps` is a minimal FS service; Harness `fs` is a richer Service Definition with hooks.
- **write-intent policy** -> **MISSING**. No veto/redirect hook before a write.
- **structured tool results** -> **PARTIALLY EXISTS** (strings vs Harness typed `ToolExecutionResult`).
- **agent loop** -> **PARTIALLY EXISTS** (`runWithTools` vs Harness `ReactLoopAgent` which adds turn/step boundaries + checkpoint).
- **tool execution flow** -> **PARTIALLY EXISTS** (sequential execute + feed-back matches Harness `executeToolCalls`).
- **native Landlock/seatbelt sandbox** -> **NOT SUITABLE FOR ANDROID** (Linux/macOS binaries; won't run on the phone).
- **Harness web UI / Python SDK / E2B** -> **NOT NEEDED** for VibeIDE.

---

## 5. PHASE 1 GAP LIST (reliability + safety)

1. Add a **per-tool timeout** (so a hung tool can't block forever) + a **max tool-iteration cap**.
2. Make **Build/Plan retry** like Chat: at least one automatic retry on 429 (honoring `Retry-After`) and on transient network errors.
3. Return **structured tool results** (`isError` + `code`) so the AI can decide retry/stop.
4. Make **tool errors distinguishable** to the model: invalid-path / file-not-found / permission / timeout as typed reasons.
5. Add a **max-iteration guard** on the tool loop.

---

## 6. PHASE 2 GAP LIST (file-tool / tool system)

1. Introduce a **central tool registry** with metadata (name, timeout, read-only/dangerous, permissions).
2. Add a **write-intent / permission policy**: protect config & important files; let `delete_file` be restricted or confirmed.
3. Unify all AI writes through **one write gate** (so no future path bypasses safety).
4. Decide the fate of the **`[[WRITE:]]` fallback**: keep as documented last-resort, but ensure it can't double-apply when tools already wrote.
5. Make it easy to add **terminal / preview / search / git / MCP** tools into the same registry without redesign.

---

## 7. WHAT WE SHOULD NOT CHANGE

- **The tool-based file editing itself** — it works and is the right design.
- **`ProjectFileOps` path-safety logic** (canonical check) — keep it; just wrap it in a registry + policy.
- **Provider error classification** (`ProviderErrorMapper`) — already good; reuse it.
- **Per-project task + history persistence** — working; don't rebuild.
- **The streaming provider path** — fixed and verified by the last build.

---

## 8. PRIORITY

- **MUST FIX FIRST:** per-tool timeout + max-iteration cap (Phase 1 #1, #5) — cheapest safety win, prevents hangs/runaways. Then Build/Plan retry (Phase 1 #2).
- **SHOULD FIX NEXT:** structured tool results + typed errors (Phase 1 #3, #4); then Phase 2 central registry + write-intent policy.
- **CAN WAIT:** terminal/git/MCP tools (need the registry first anyway); true mid-task resume.
- **NOT NEEDED:** copying Harness's native sandbox, web UI, or Python SDK.

---

## 9. PROPOSED PHASE 1 PLAN (do not implement yet)

1. In `ChatEngine.runWithTools`, wrap each `execute(call)` with a timeout (e.g. 60–120s) and a max loop count (e.g. 20 tool rounds). On timeout, return a structured "tool timed out" result to the model instead of hanging.
2. In `AIChatActivity.runTask`, give Build/Plan the same one-shot 429/network retry that Chat already has (reuse the existing `callProvider` retry logic, honor `Retry-After`).
3. Change `ProjectFileOps.dispatch` to return a small structured result (`success`/`isError` + `code` + `message`) instead of a bare string; keep the text too so the model still understands it.
4. Map tool failures to typed reasons: `INVALID_PATH`, `FILE_NOT_FOUND`, `PERMISSION`, `TOOL_TIMEOUT`.
5. Add a unit-level check (or manual trace) that a tool failure no longer stops the loop and that an over-long loop is cut off.

---

## 10. PROPOSED PHASE 2 PLAN (do not implement yet)

1. Create a `Tool` registry (list of definitions with metadata: `timeoutMs`, `readOnly`, `dangerous`, `permission`). Move the current 4 tools into it.
2. Route every AI write through one `FileWriteGate` that enforces: still-inside-project (reuse `ProjectFileOps.resolve`) **and** a policy (e.g. refuse to delete/overwrite a deny-list like `package.json`, or require a flag for dangerous tools).
3. Keep `[[WRITE:]]` as fallback only; make sure when tools already wrote, the fallback is skipped (it already is via `changedFiles==0`, but add an explicit guard so both can never apply).
4. Add the missing tools into the same registry as the architecture allows: `terminal` (driven by a persistent shell), `preview`, `search`, `git`, `mcp` — each declaring its own timeout/permission so Phase 1's guard covers them automatically.

---

## 11. BIGGEST RISK

**The tool loop has no timeout and no iteration limit.** Right now it's safe because the only tools are fast local file ops. The moment you add a terminal or any long/blocking tool (which Phase 2 wants), a single stuck command — or a model that loops tool calls — will hang or run away the whole task with no recovery. Phase 1's per-tool timeout + loop cap must land *before* any new tool, or you'll be debugging invisible hangs on a phone with no local debugger.

---

## 12. FINAL SHORT SUMMARY

Right now VibeIDE can **let the AI create and edit many project files through real app-executed tools, safely kept inside the project, with well-classified provider errors and a resumable task** — that part is solid. But it **cannot reliably survive a slow/hanging tool, a rate-limit during a Build, or an unbounded tool loop**, because there is no per-tool timeout, no Build retry, and no tool-result structure.

**Phase 1 should add:** a per-tool timeout + loop cap, Build/Plan auto-retry on 429/network, and structured tool errors.

**Phase 2 should add:** a central tool registry with metadata, a single write gate with a permission/deny-list policy, and an easy path to add terminal/preview/git/MCP tools.
