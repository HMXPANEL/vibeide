# VIBEIDE_ARCHITECTURE.md

Target architecture for **VibeIDE** — a premium mobile-first AI vibe-coding IDE
for creating **web applications** on Android devices.

> Identity: *"Describe it. Build it. Preview it."*

---

## 1. Design principles

1. **Web-first, not Android-first.** Every product decision answers: *"Does this
   help a user create, edit, run, preview, debug, or deploy a WEB application?"*
   NO → remove, isolate, or replace.
2. **Mobile-first UX.** Touch-friendly toolbar, large type, bottom sheets,
   thumb-reach controls. No desktop-only affordances.
3. **AI-native loop.** The AI agent is a first-class citizen with real tools —
   never fake tool calls.
4. **Keep generic infrastructure.** Editor, file explorer, workspace, terminal,
   git, logging, settings, navigation are retained from AndroidIDE and adapted.
5. **GPL-3.0 compliance.** All reused AndroidIDE code stays under GPL-3.0 with
   attribution preserved (see `THIRD_PARTY_NOTICES.md`).

---

## 2. Tech stack (retained from AndroidIDE)

| Layer | Choice | Status |
|---|---|---|
| UI | AndroidX (appcompat, navigation, material, fragment, recyclerview, constraintlayout, preference, work, security-crypto) | KEEP |
| Editor | Rosemoe **sora-editor** 0.23.4 + tree-sitter integration (`editor/impl`, `editor/treesitter`) | KEEP |
| Highlighting | tree-sitter (`editor/treesitter` + `editor/impl` scheme machinery) | KEEP |
| Event dispatch | GreenRobot EventBus fork (`event/eventbus*`) + kapt index generation (`annotation/processors`) | KEEP |
| Logging | logback (Android-patched via composite build) (`logging/logger`) | KEEP |
| Service locator | `utilities/lookup` (JVM ServiceLoader) | KEEP |
| Git | JGit 6.8 (`core:app`) | KEEP |
| Persistence | SharedPreferences wrappers (`utilities/preferences`), Realm for AI memory (`core:app/ai/memory`) | KEEP |
| AI HTTP | Retrofit + Gson | KEEP |
| File tree | `utilities/treeview` (AndroidTreeView) | KEEP |
| Onboarding | appintro (composite build) | KEEP |
| Markdown | markwon | KEEP (About/changelog rendering) |

**Removed**: Gradle tooling server (`tooling/*`), on-device javac + Java LSP
(`java/*`), Android XML tooling (`xml/*`), layout designer + inflater
(`utilities/uidesigner`, `utilities/xml-inflater`), Android templates
(`utilities/templates-*` — replaced), user-app log forwarding (`logging/logsender*`),
Java/Kotlin/XML tree-sitter grammars, ANTLR lexers.

---

## 3. Module layout after migration

```
annotation/
  processors            KEEP   (EventBus index generation)
core/
  actions               KEEP   (editor/sidebar action registry)
  app                   ADAPT  (VibeIDE app shell)
  common                ADAPT  (process exec, themes; drop Android toolchain env)
  indexing-api          KEEP
  knowledge-api         ADAPT  (web file context for AI)
  lsp-api               ADAPT  (decoupled from core:projects / xml:utils)
  lsp-models            KEEP   (generic LSP models)
  projects              REPLACE (web project model, no Gradle)
  resources             ADAPT  (VibeIDE's own UI resources; rebrand later)
editor/
  api                   KEEP
  impl                  ADAPT  (drop Java/Kotlin/XML/Cpp/Groovy; add web langs)
  treesitter            KEEP
event/eventbus{,,-android,,-events}  KEEP
logging/
  logger                KEEP
  idestats              KEEP
utilities/
  build-info, flashbar, framework-stubs, lookup, preferences,
  shared, treeview      KEEP
web/                     NEW    (future: web templates, preview runtime,
                                HTML/CSS/JS/TS language servers)
```

---

## 4. Core screens (target)

1. **Splash** — VibeIDE brand, checks state.
2. **Onboarding** — first-run, AI provider setup.
3. **Home** — recent web projects, quick actions (New Project / Open / Clone / AI).
4. **New Project** — web template gallery **or** "Start with AI" natural-language
   project generation.
5. **AI Vibe Coding** — chat-driven create/edit/fix loop.
6. **File Explorer** — mobile-first tree, full file ops.
7. **Code Editor** — HTML/CSS/JS/JSON/Markdown, mobile symbol toolbar.
8. **Live Preview** — real web runtime (WebView) with refresh/fullscreen/
   device-frame + console.
9. **Console** — build/run output, JS console, runtime errors, AI actions.
10. **Problems** — diagnostics panel.
11. **Project Settings** — preview config, AI context, checkpoints.
12. **Appearance** — dark-first premium themes.
13. **Git** — status/diff/commit/branch/pull/push (JGit).
14. **GitHub** — auth, repo browser, import/push (future).
15. **Export** — zip/export web project (future).
16. **About** — attribution, licenses, version.
17. **AI Settings** — providers/models (already present: gemini, claude, openai,
    deepseek, opencode, etc.).

---

## 5. AI agent architecture

```
User Request
     │
     ▼
┌─────────────────────┐
│  AI Agent (core:app │  tools: list_files, read_file, create_file,
│  /ai, chat engine)  │        edit_file, delete_file, rename_file,
└─────────┬───────────┘        search_files, run_project, get_console,
          │                    get_errors, apply_fix, create_checkpoint,
          ▼                    restore_checkpoint
  Project Context
  (core:app/ai/context: ProjectScanner, ProjectIndex, ProjectAnalyzer)
          │
          ▼
  Project Store (web files on device via SAF)
          │
          ▼
  Edit files → Run web project → Capture errors → AI fix → Preview
```

### Agent tool semantics (no fake calls)

| Tool | Behaviour |
|---|---|
| `list_files` | Real directory listing of the open project. |
| `read_file` | Real file read (with size cap). |
| `create_file` / `edit_file` / `delete_file` / `rename_file` | Real FS operations. Edits are applied to the file buffer and trigger re-analysis + re-highlight. |
| `search_files` | Text search across project (reuse `RecursiveFileSearcher`). |
| `run_project` | Starts the web preview runtime (static server + WebView). |
| `get_console` | Returns captured console/build output. |
| `get_errors` | Returns current diagnostics (Problems panel). |
| `apply_fix` | Applies a generated diff via the normal edit path. |
| `create_checkpoint` / `restore_checkpoint` | Snapshot/restore project files (zip copy in `.vibeide/checkpoints`). |

### Repair loop (finite)

`run → error? → capture → identify file → context → generate fix → apply → run`
— maximum retries is a **configurable, finite** setting (default 3). Never an
infinite loop; on exhaustion, report and hand back to the user.

---

## 6. Web preview architecture

```
index.html / style.css / script.js
        │
        ▼
 static file server (local HTTP via java.net ServerSocket, no deps)
        │
        ▼
 Android WebView (JS enabled, file/HTTP base)  ──►  console bridge
        │                                            (page console → VibeIDE Console)
        ▼
 Live Preview UI: refresh, fullscreen, device frame
 (mobile / tablet / desktop viewport presets)
```

- **Runtime**: WebView with `JavaScriptEnabled`, served over `http://127.0.0.1:<port>`
  so relative paths, fetch, modules, and history work (no `file://` CORS pain).
- **Preview errors**: bridge `console.log/error`, uncaught exceptions and HTTP
  fetch failures back to the Console + Problems tabs.
- **Future**: `npm install`/`npm run dev` through the terminal for Node/Vite/
  React projects.

---

## 7. Web project model (`core:projects` replacement)

```kotlin
data class WebProject(
  val name: String,
  val path: String,            // SAF-backed or direct filesystem
  val activeFile: String?,
  val settings: WebProjectSettings,  // preview config, AI context
  val checkpoints: List<Checkpoint>
)
```

Plain directory-of-web-files model; no Gradle, no modules, no classpaths, no
variants. `IProjectManager`/`IWorkspace`/`FileManager` keep operating as the
generic workspace layer.

---

## 8. Editor languages (priority)

| Priority | Language | Tree-sitter grammar |
|---|---|---|
| P0 | HTML | `tree-sitter-html` |
| P0 | CSS | `tree-sitter-css` |
| P0 | JavaScript | `tree-sitter-javascript` |
| P0 | JSON | **already bundled** (`JsonLanguage`) |
| P0 | Markdown | sora `MarkdownLanguage` or tree-sitter-markdown |
| P1 | TypeScript / TSX / JSX | `tree-sitter-typescript` |
| P1 | React / Vite / Tailwind | tooling via terminal (`npm`) |

---

## 9. Mobile editor toolbar

Symbol row `{ } ( ) [ ] < > / = ; Tab` (`SymbolInputView` already exists in
`core:app/ui`) + action row: Undo, Redo, Search, Go to line, Save, **AI edit**.

---

## 10. Git

JGit already provides clone + commit/push (`core:app/build/ProjectGitInfo.kt`,
`MainFragment` clone flow). Extend with status/diff/branch/pull/push UI; later
GitHub auth + repo browser + import/push.

---

## 11. Checkpoint plan

- `checkpoint-01-analysis` — migration map + docs (current).
- `checkpoint-02-android-feature-removal` — module deletion + app surgery.
- `checkpoint-03-module-cleanup` — resources, manifest, permissions, dead code.
- `checkpoint-04-vibeide-shell` — web project model, templates, preview, branding.