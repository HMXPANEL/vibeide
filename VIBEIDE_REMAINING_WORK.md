# VibeIDE Remaining Work

Known gaps and deferred work after checkpoint-04 and Phase 5. Items are ordered
by impact. Nothing here blocks the APK build.

## Terminal (NOT IMPLEMENTED)

The in-app terminal is **not implemented**. The old AndroidIDE terminal module
was removed with the Gradle tooling. Consequences:

- No `npm install` / `npm run dev` / `npm run build` for Node/Vite/React
  projects.
- `run_project` AI tool can only start the static preview server; it cannot run
  a dev server.
- Console output is limited to the WebView console bridge + static server
  errors.

**When to add**: after the APK ships, a real terminal requires a JNI PTY layer
or a `Process`-based shell wrapper with a `PseudoTerminal`-style emulator; see
`VIBEIDE_ARCHITECTURE.md` § 3 (terminal retained-but-adapted).

## Web language syntax highlighting

HTML/CSS/JS/Markdown currently get a lightweight `WebLanguage` with **no syntax
highlighting** (no tree-sitter grammar or TextMate bundle is bundled).

**When to add**: bundle `tree-sitter-html/css/javascript` grammars + `.scm`
queries, or add sora `language-textmate` grammars. JSON already highlights via
`JsonLanguage`.

## TypeScript / React / Vite tooling

TS/TSX/JSX files are editable (basic `WebLanguage`) but there is no build tool
until the terminal exists. No TypeScript language server is bundled.

**When to add**: terminal + `npm` (Node projects), then LSP servers for
JS/TS via `editor-lsp`.

## Git UI

JGit clone exists (`MainFragment`); status/diff/branch/pull/push UI, GitHub
auth and repo browser are future work.

## Web project export

Zip/export of a web project is not implemented.

## AAPT / Android user-project tooling (all removed)

Gradle tooling server, on-device javac, Java/XML/Cpp language servers, layout
designer/inflater, Android templates, and user-app log forwarding are gone and
are **not coming back**.

## Misc cleanups

- `SaveResult.gradleSaved` / `saveAll(..., processResources, ...)` are AndroidIDE
  leftover names in VibeIDE's own save flow; rename when touching that code.
- Unused file-tree drawables (`ic_language_java`, `ic_language_kotlin`,
  `ic_language_xml`, `ic_gradle`, `ic_file_apk`, `ic_terminal`, ...) remain in
  `core/resources`; safe to remove.
- `VIBEIDE_MODULE_MIGRATION.md` / `VIBEIDE_REMOVALS.md` document the old
  `com.hmx.ide` package historically; left as-is intentionally.