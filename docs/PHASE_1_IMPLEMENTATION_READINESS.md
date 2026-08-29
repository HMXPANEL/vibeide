# Phase 1 Implementation-Readiness Analysis

> **Analysis only.** No source file was modified while producing this document.
> Purpose: confirm the current code, map the Build/Plan flow, and pinpoint exactly where each
> Phase 1 change should go. Implementation is NOT done here.

Reference files inspected (all current, unchanged since the earlier analysis):
- `core/app/.../ai/engine/ChatEngine.kt`
- `core/app/.../activities/aichat/AIChatActivity.kt`
- `core/app/.../ai/providers/openai/OpenAiProvider.kt`
- `core/app/.../ai/network/AiHttpClient.kt`
- `core/app/.../ai/network/ProviderErrorMapper.kt`
- `core/app/.../ai/errors/AiException.kt`
- `core/app/.../ai/tools/ProjectFileOps.kt`
- `core/app/.../activities/aichat/ChatTask.kt`, `ChatTaskStore.kt`

---

## 1. Current system verified

The earlier report is **still accurate** — I re-read every involved file and nothing changed.

Confirmed working (do NOT rebuild these):
- **Build/Plan tool execution works.** `AIChatActivity.runTask` (line 407-408) calls `chatEngine.runWithTools(...)` with `fileOps.dispatch` as the executor when `mode != "chat"` and the provider has `Capability.tools`. (`OpenAiProvider.capabilities` = `streaming, tools, functionCalling`.)
- **Sequential multi-tool execution works.** `ChatEngine.runWithTools` (lines 117-160) loops: model returns tool calls -> `execute(call)` runs each one (line 154) -> result is fed back as a `Role.tool` message (lines 155-157) -> loops again until no more tool calls.
- **Project path safety works.** `ProjectFileOps.resolve` (lines 63-74) rejects absolute paths (line 66), `..` (line 65), and uses the *canonical* (symlink-resolved) path to confirm containment (lines 68-72). All four tools call `resolve`.
- **Provider error classification works.** `ProviderErrorMapper` maps 402/429/quota -> `QuotaException`, 429 -> `RateLimitException(retryAfterSeconds)`, 401/403 -> `AuthenticationException`, 404 -> `ModelNotFoundException`, 400/422 -> `ProviderException`, 5xx -> `NetworkException`.
- **Task persistence works.** `ChatTaskStore` saves to `<project>/.androidide/chat_task.json` on every chunk; `continueTask` resumes a FAILED/INTERRUPTED task from its original prompt.
- **No duplicate-send.** `sendMessage` (lines 330-334) blocks while a task is RUNNING/WORKING.

Confirmed gaps (these are Phase 1):
- No per-tool timeout. Only a 10-minute *whole-request* HTTP read timeout (`OpenAiProvider` `readTimeout = 600_000`, lines 54, 66).
- No maximum tool-loop iteration. `runWithTools` is `while (true)` (line 117).
- **Build/Plan has no retry on 429.** Only Chat mode retries (inside `callProvider`, lines 474-481) — and only when `retryAfterSeconds > 0`.
- Tool results are plain `String`s (no `isError`/`code`).
- Tool errors are free-text (`"Error: ..."`), not typed.

> Ye already working hai, isliye ise dobara nahi banana. Sirf gap wale parts ko add karna hai.

---

## 2. Current Build/Plan flow (simple language)

```
User types request, taps Send
  -> sendMessage() blocks re-sends, creates ChatTask(RUNNING), saves it
  -> runTask(task, prompt)
       -> mode is build/plan + provider has tools
            -> chatEngine.runWithTools(model, prompt, tools, onChunk, execute = fileOps.dispatch)
                 -> loop:
                      send messages (+ tools) to AI provider   [OpenAiProvider.stream]
                      provider streams tokens + tool_calls
                      if tool_calls present:
                           for each call:  fileOps.dispatch(call)   <-- APP does the file work
                           result string fed back as Role.tool message
                           loop again
                      else:
                           return final assistant answer
            -> collectEdits(content)  (legacy [[WRITE:]] scan; used only if tools wrote nothing)
            -> mark ChatTask COMPLETED, save, persist history
       -> on error: mark ChatTask FAILED, show Continue button, friendlyError()
```

Where each Phase 1 item plugs in:
- **Per-tool timeout** -> wrap `execute(call)` (ChatEngine line 154).
- **Tool-loop limit** -> the `while (true)` (ChatEngine line 117).
- **Build/Plan retry** -> wrap the `chatEngine.runWithTools(...)` call in `runTask` (AIChatActivity line 408), reusing the same retry shape as `callProvider`.
- **Structured + typed tool results** -> `ProjectFileOps.dispatch` (currently returns `String`) + how `runWithTools` converts the result into the `Role.tool` message.

---

## 3. Per-tool timeout — exact integration point

**Where:** `ChatEngine.runWithTools`, line 154:
```kotlin
finalCalls.forEach { call ->
  val result = execute(call)                 // <-- wrap this with withTimeout
  messages.add(ChatMessage(role = Role.tool, content = result, toolCallId = call.id))
  ...
}
```

**Which function owns the timeout?** `ChatEngine.runWithTools`. It is the single place every tool execution passes through, so the timeout is centralized and automatically covers future tools (Phase 2) too. Do not scatter timeouts inside `ProjectFileOps`.

**Timeout value for current tools:** current tools are local file ops (read/write/list/delete) — they finish in milliseconds. **30 seconds is more than enough** (`TOOL_TIMEOUT_MS = 30_000`). This leaves huge headroom but kills any genuinely stuck op.

**Different tools later?** Yes, Phase 2's tool registry should carry a per-tool `timeoutMs`. For Phase 1 a single constant is correct and minimal. (Harness uses a per-tool `TOOL_TIMEOUT`; we mirror the *idea*, not the code.)

**On timeout:** catch `TimeoutCancellationException`, convert it into a structured tool error (code = `TIMEOUT`) and **return it as the tool result** — do NOT throw it out of the loop.
```kotlin
val result = try {
  withTimeout(TOOL_TIMEOUT_MS) { execute(call) }
} catch (e: TimeoutCancellationException) {
  ToolResult(isError = true, code = "TIMEOUT",
             message = "Tool '${call.name}' timed out after ${TOOL_TIMEOUT_MS/1000}s")
}
```

**Does the AI receive the timeout?** Yes — as a normal tool result string, so the model can react ("that file op hung, I'll try a different approach").

**Does the whole task stop?** **No.** The loop continues. This is the safe choice: one slow tool must not abort an entire build.

> Yahan timeout lagana safest hai — `execute(call)` ke exactly upar `withTimeout` wrap karna. Poora task fail nahi hoga, sirf wo tool ka result "timeout" aayega aur AI aage badhega.

---

## 4. Tool-loop limit — exact integration point

**Where:** `ChatEngine.runWithTools`, the `while (true)` at line 117.

**Current limit:** none. The loop runs as long as the model keeps returning tool calls. A looping model could run unbounded.

**Recommended:** add a counter and a constant.
```kotlin
var rounds = 0
val MAX_ROUNDS = 20
while (true) {
  rounds++
  ... existing body ...
  if (finalCalls.isEmpty()) return ChatResponse(message = assistantMsg)
  if (rounds >= MAX_ROUNDS) {
    // Stop asking for more tools; let the model produce a final answer without tools.
    val finalReq = engine.buildChatRequest(model, messages, systemPrompt, stream = true, tools = emptyList())
    ... collect one last answer ...
    return ChatResponse(message = lastAssistantMsg with a note "⚠ Reached max tool rounds")
  }
  finalCalls.forEach { ... }
}
```

**On reaching the limit:** do NOT crash. Make one last model call *without* tools so the AI writes a closing message, then return. Add a short warning to that final content so the user sees it hit the cap.

**Who is informed:**
- **AI:** via the final tool-less call (it just produces a summary).
- **User:** the task still completes (status COMPLETED) and the warning text appears in the chat bubble. No new dialog/toast needed.

> Loop limit `while(true)` ke andar `rounds` counter se lagana hai. Poocho mat "kab tak chalega" — 20 round fixed rakho, uske baad last baar bina tool ke answer maango aur ruk jao.

---

## 5. Build/Plan retry — exact integration point

**What already retries:**
- *Transient network errors* (generic `Exception`): already retried up to 2x at the HTTP layer (`AiHttpClient.retryOnTransientError`, lines 74-82). This applies to BOTH chat and Build/Plan.
- *429 with Retry-After*: **only in Chat mode**, at the app layer inside `callProvider` (lines 474-481). `AiHttpClient` does NOT retry `AiException` (lines 70-73), and `RateLimitException` is an `AiException`, so the HTTP layer rethrows it.

**What does NOT retry:** Build/Plan. `runTask` calls `chatEngine.runWithTools(...)` directly (line 408) with no retry around it.

**Safest reuse (no second retry system):** extract the existing `callProvider` retry logic into one small shared helper and use it for BOTH paths.
```kotlin
private suspend fun <T> withProviderRetry(block: suspend () -> T): T {
  var attempt = 0
  while (true) {
    try { return block() }
    catch (e: RateLimitException) {
      if (attempt < 1 && (e.retryAfterSeconds ?: 0L) > 0L) {
        attempt++
        delay((e.retryAfterSeconds!!).coerceAtMost(60L) * 1000L)
      } else throw e
    }
  }
}
```
Then:
- `callProvider` becomes `return withProviderRetry { chatEngine.stream(...)/send(...) }`.
- `runTask`'s build/plan branch becomes `withProviderRetry { chatEngine.runWithTools(...) }`.

**What should NOT be retried:** `QuotaException` (no balance — retrying won't help), `AuthenticationException` (bad key), `ModelNotFoundException`, `ProviderException` (bad request), and **tool-execution results** (those are returned as results, not thrown, so they naturally fall outside the retry). The helper only catches `RateLimitException`, so all others pass straight through.

> Build/Plan retry ke liye naya system mat banana. `callProvider` wala 429 retry exactly copy karke ek chhota `withProviderRetry` helper banao, aur Build/Plan wali `runWithTools` call uske andar wrap kar do. Dono ek hi tareeke se retry karenge.

---

## 6. Structured tool result design

**Current:** `ProjectFileOps.dispatch` returns `String`. `ChatEngine.runWithTools` stores that string as the `Role.tool` message `content`. The provider API sends tool results as `ChatMessage(content: String, toolCallId)` — there is **no structured field** in the current provider API. So we keep `content` as a string and *generate* it from a structured object. No provider/serialization change needed -> backward compatible.

**Smallest structured result** (new tiny data class, e.g. `ai/tools/ToolResult.kt`):
```kotlin
data class ToolResult(
  val isError: Boolean,
  val code: String?,      // INVALID_PATH / FILE_NOT_FOUND / PERMISSION / TIMEOUT / UNKNOWN_TOOL / null
  val message: String,    // human + AI readable text
)
```

**Conversion to provider:** `ToolResult.toContent()` -> if `isError` return `"[$code] $message"` else `message`. The `Role.tool` message keeps using `content = result.toContent()`. The model still reads plain text, so nothing downstream changes.

**Where it lives:** new file `ai/tools/ToolResult.kt` (or add to `ChatModels.kt`). The `execute` lambda type in `runWithTools` changes from `suspend (ToolCall) -> String` to `suspend (ToolCall) -> ToolResult`; only `AIChatActivity` supplies that lambda, so it is a safe, contained change.

**Backward compatibility:** chat mode never uses `runWithTools`, so chat is untouched. The `[[WRITE:]]` fallback is untouched.

---

## 7. Typed tool errors

**Errors already produced by `ProjectFileOps` (today as free text):**
- `invalid or out-of-project path` / absolute / `..` -> **INVALID_PATH**
- `file not found` / `not a directory` / `not a file (refusing to delete directory)` -> **FILE_NOT_FOUND**
- `could not read` / `could not write` / `could not delete` / `cannot create parent dirs` -> **PERMISSION** (or a generic **INTERNAL** for plain I/O)
- `unknown tool '...'` -> **UNKNOWN_TOOL**
- anything caught by `runCatching` -> **INTERNAL**
- new: per-tool timeout -> **TIMEOUT**

**A (AI agent):** the tool result string carries the code prefix, e.g. `"[FILE_NOT_FOUND] file not found: src/x.js"`. The model uses it to self-correct. All typed tool errors go to the AI.

**B (User UI):** tool errors should NOT become separate user toasts — they are recoverable and the AI handles them inside the task. The user sees them only as part of the conversation (the assistant/tool messages). The *user-facing* top-level errors stay exactly as today: `friendlyError` maps provider/network failures (`QuotaException`, `RateLimitException`, `AuthenticationException`, `NetworkException`, `SocketTimeoutException`, `ConnectException`) to messages. Tool errors do not change that mapping because they are not `AiException`s.

> Tool error AI ko jayega `[CODE] message` format me, user ko alag popup nahi. User sirf final answer dekhega. Provider wale error (429, quota, auth) user ko `friendlyError` se dikhte rahenge — wo mat chhedo.

---

## 8. Existing safety verification

`ProjectFileOps.resolve` (lines 63-74) was checked line by line:
- absolute paths blocked: `if (File(cleaned).isAbsolute) return null` (line 66) ✅
- `..` traversal blocked: `if (cleaned.contains("..")) return null` (line 65) ✅
- canonical paths checked: `root.canonicalPath` vs `file.canonicalPath` (lines 68-72) ✅
- symlinks outside project blocked: canonical comparison catches symlink escape ✅
- all 4 tools (read/write/list/delete) call `resolve` ✅

**Phase 1 must NOT replace this.** Timeout + structured results are added *around* `dispatch`; `resolve` stays exactly as-is. If Phase 2 later adds a write-gate/permission policy, it builds on top of `resolve`, not instead of it.

> Is part ko touch nahi karna chahiye — `ProjectFileOps.resolve` already perfect hai (absolute, `..`, symlink sab block hota hai). Phase 1 sirf `dispatch` ke return type aur `runWithTools` me timeout/limit add karega, `resolve` chhuta rahega.

---

## 9. Files / classes that WILL need modification

(Do NOT modify them now — this is the plan only.)
- **`ChatEngine.kt`** — `runWithTools`: change `execute` return type to `ToolResult`; wrap `execute(call)` in `withTimeout`; add `MAX_ROUNDS` loop counter; convert `ToolResult` -> content string.
- **`ProjectFileOps.kt`** — `dispatch` returns `ToolResult` with a `code`; keep `resolve` untouched.
- **`AIChatActivity.kt`** — `runTask`: wrap the `runWithTools` call in the shared `withProviderRetry` helper; the `fileOps.dispatch` lambda now returns `ToolResult` automatically. Add the `withProviderRetry` helper (extracted from `callProvider`).
- **`ai/tools/ToolResult.kt`** (NEW small file) — the `ToolResult` data class. (Could alternatively live in `ChatModels.kt`; either is fine.)

No `AiException` change is needed: the per-tool timeout becomes a `ToolResult(code="TIMEOUT")`, not a thrown exception.

---

## 10. Files / classes that MUST NOT be modified

- **`AiHttpClient.kt`** — its retry policy is intentionally conservative (no retry on `AiException`/timeout). Leave it.
- **`ProviderErrorMapper.kt`** — already correct; reuse it.
- **`OpenAiProvider.kt`** — provider behavior, streaming and `tool_calls` parsing all work; don't change.
- **`ChatTask.kt` / `ChatTaskStore.kt`** — task persistence format works; don't change.
- **`ChatHistoryStore.kt`** — don't change.
- **`ProjectFileOps.resolve`** — path safety; don't replace.
- **`PreviewActivity.kt` / `web/ProjectPreview.kt`** — Phase 1 doesn't touch Preview.
- **`PromptBuilder.kt`** — system prompt unchanged for Phase 1.
- **`applyEdits` / `collectEdits`** — legacy `[[WRITE:]]` fallback; leave as-is (Phase 1 doesn't need it).

---

## 11. Regression risks

| Area | Risk | How to avoid breakage |
|------|------|------------------------|
| Normal Chat | retry helper accidentally changes chat path | Route chat through the *same* `withProviderRetry`; behavior identical to today (only `RateLimitException`+`retryAfter`). |
| Build | retry retries the wrong thing (e.g. a tool error) | Helper catches ONLY `RateLimitException`. Tool errors are returned as results, never thrown, so they fall outside retry. |
| Plan | same as Build | same guard. |
| Streaming | timeout/loop changes leak into chat stream | Changes live only in `runWithTools`; `callProvider`->`chatEngine.stream` is untouched. |
| Task Continue | runTask throwing differently breaks Continue | Keep `runWithTools` throwing only on real provider/network failure; `CancellationException` still handled as INTERRUPTED (lines 430-436). Continue reuses `runTask` automatically. |
| Project history | tool message format changes | Tool result still a `Role.tool` message with a `content` string (from `ToolResult.toContent()`); history save unchanged. |
| Provider errors | new typed tool errors confuse `friendlyError` | Tool errors are NOT `AiException`s, so `friendlyError` is unaffected. |
| File editing | writes change behavior | `dispatch` still does the write via `resolve`; only its return type changes. `changedFiles` counter unchanged. |
| Preview | none | not touched. |
| `withTimeout` cancellation | half-written file / leaked coroutine | Local file ops finish in ms; 30s timeout won't trigger for current tools. Cancellation is caught and turned into a `TIMEOUT` result, never propagated as task failure. |

---

## 12. Recommended implementation order

1. **Add `ToolResult`** data class (`ai/tools/ToolResult.kt`).
2. **`ProjectFileOps.dispatch`** returns `ToolResult` with codes (keep `resolve` untouched).
3. **`ChatEngine.runWithTools`**: accept `execute: suspend (ToolCall) -> ToolResult`; wrap in `withTimeout(30_000)`; add `MAX_ROUNDS = 20`; convert `ToolResult` -> content string.
4. **`AIChatActivity`**: extract `withProviderRetry` helper; use it for both `callProvider` and the `runWithTools` call in `runTask`.
5. **CI build + unit tests** (see plan below).

Do the MUST-FIX-FIRST items (steps 2-3: timeout + loop cap) first, then retry (step 4), then structured/typed results are already part of steps 1-3.

---

## 13. Simple test plan

- **Unit — `ProjectFileOps`:** assert `dispatch` returns `ToolResult` with:
  - `FILE_NOT_FOUND` for a missing file,
  - `INVALID_PATH` for `".."` and for an absolute path,
  - `UNKNOWN_TOOL` for an unknown name,
  - `isError = false` for a successful `write_file` (and `changedFiles` incremented).
- **Unit — `ChatEngine.runWithTools`:** with a fake `execute` that never returns, assert a `TIMEOUT` result is produced and the loop continues (task does not crash). With a fake `execute` that always returns a tool call, assert the loop stops after `MAX_ROUNDS` and a final answer is returned.
- **Unit — `withProviderRetry`:** returns successfully after one `RateLimitException(retryAfter=2)`; does NOT retry `QuotaException`.
- **Manual/CI:** a normal Chat still completes (no regression); a Build with a tool that errors returns the error to the AI and continues; CI compiles + runs the above unit tests.
- **Cannot verify on-device here:** real streaming UX and real `Continue` after a kill — logic is connected; confirm on a device after CI is green.

---

## 14. Final recommendation (simple language)

**Project is ready for Phase 1.** The risky parts (tool execution, path safety, provider error mapping, task persistence) are already isolated and can stay untouched. Phase 1 changes are small and local:

- `runWithTools` gets a **timeout** + a **loop limit** (safest point: exactly around `execute(call)` and the `while(true)`).
- `ProjectFileOps.dispatch` returns a small **`ToolResult`** with an error **code** (path safety NOT touched).
- Build/Plan gets the **same 429 retry** Chat already has, by extracting one shared `withProviderRetry` helper — no second retry system.

Kuch rebuild nahi karna. Jo already chal raha hai (tools, path safety, error mapping, task save) use hum chord denge. Phase 1 sirf 3 chote additions hai: tool timeout, loop limit, aur Build/Plan ke liye 429 retry. Sab `runWithTools` aur `dispatch` ke andar hi band payega, baaki code `AiHttpClient`, `ProviderErrorMapper`, `OpenAiProvider`, `ChatTask` sab *as-is* rahega.

**Sabse pehle karo:** per-tool timeout + loop limit (MUST-FIX-FIRST). Fir Build/Plan retry. Structured + typed errors automatically step 1-3 me aa jayenge.

**Verdict: GO for Phase 1 implementation.** Start with the MUST-FIX-FIRST items, verify via CI, then proceed.
