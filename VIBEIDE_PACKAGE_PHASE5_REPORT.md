# VibeIDE Phase 5 Report — Finish Web Workspace Migration

Follows the package rename to `com.hmx.webide` (`VIBEIDE_PACKAGE_MIGRATION.md`).
Removes the remaining Android user-project assumptions so the IDE, AI context,
and knowledge index operate on web projects.

## 1. Web project detection (new)

`core/app/.../ai/context/WebProjectType.kt`

- `WebProjectType`: `STATIC_HTML`, `NODE_PROJECT`, `VITE_PROJECT`,
  `REACT_PROJECT`, `UNKNOWN_WEB_PROJECT`.
- `WebLanguage`: `HTML`, `CSS`, `JAVASCRIPT`, `JSON`, `MARKDOWN`, `TYPESCRIPT`,
  `UNKNOWN`.
- `WebProjectDetector.detect(root)`:
  - no `index.html` -> `UNKNOWN_WEB_PROJECT`
  - `vite`/`@vitejs/plugin-react` in package.json -> `VITE_PROJECT`
  - `react`/`react-dom` -> `REACT_PROJECT`
  - any dependencies or a `scripts` block -> `NODE_PROJECT`
  - otherwise -> `STATIC_HTML`
- `readPackageJson(root)` parses dependencies/devDependencies/peerDependencies/
  optionalDependencies.

## 2. AI context is web-oriented

`core/app/.../ai/context/` rewritten:

| File | Change |
|---|---|
| `ProjectContext.kt` | Replaced Android fields (packageName, activities, fragments, services, receivers, providers, minSdk/targetSdk/compileSdk, hasApplicationClass, modules, buildSystem, ui, architecture) with `projectType`, `languages`, `entryFile`, `hasPackageJson`, `dependencies`, `scripts`, `fileCount`, `dirs`. |
| `ProjectScanner.kt` | Scans web files only (`html, htm, css, js, mjs, cjs, json, md, txt, yml, yaml, ts, tsx, jsx, vue, svg`). Skips `node_modules`, `dist`, `build`, `.git`, etc. Collects JS `import`/`export`, HTML `script src`, CSS `@import`; function/class/const names. Removed AndroidManifest/build.gradle/kotlin/java/xml-layout collection. |
| `ProjectAnalyzer.kt` | Uses `WebProjectDetector`; detects languages, dependencies, npm scripts, entry file, top-level dirs. Removed manifest/Gradle SDK parsing. |
| `ContextCache.kt` | Staleness tracked via `index.html`, `package.json`, `vite.config.js` mtimes instead of manifest/Gradle files. |
| `PromptBuilder.kt` | Web-oriented system prompt, startup message, and project analysis (type, languages, entry, deps, scripts, dirs). |
| `ProjectContextSummary.kt` | Display summary now reports `projectType`, `languages`, `entryFile`, `dependencyCount`. Kept `projectDir/projectName/totalFiles/lastIndexedAt/state/progress/hasContext` used by the AI chat UI. |
| `ContextPipeline.kt` | `collectProjectStructure()` emits web project info; removed Android sections. |

## 3. Knowledge engine / indexer recognize web files

`core/app/.../knowledge/`

- `FileParser.kt` now extracts declarations from:
  - JS/TS: `function`, `class`, `const/let/var`, `export` -> `FUNCTION`/`CLASS`/`VARIABLE`
  - HTML: `id` and `class` attributes -> `KEY`
  - CSS: selectors -> `SELECTOR`
  - JSON: top-level keys -> `KEY`
  - Markdown: headings -> `HEADING`
- `IncrementalIndexer.kt`: indexes web extensions instead of only `.java`/`.kt`;
  declarations are added regardless of `packageName`.
- `KnowledgeEngineImpl.kt`: project modules now come from top-level web
  directories; all web-file declarations are added to the symbol index;
  `WEB_EXTENSIONS` includes `htm`/`mjs`/`cjs`.
- `core/knowledge-api/.../ProjectModel.kt`: `SymbolKind` gains `FUNCTION`,
  `VARIABLE`, `SELECTOR`, `HEADING`, `KEY`.

## 4. LSP API is generic

`core/lsp-api` was already generic LSP models over `Path`/`IWorkspace` — no
Android/Java project coupling. No changes required.

## 5. Editor distinguishes web languages

- New `WebLanguage` (`editor/impl/.../language/WebLanguage.kt`) — lightweight
  `IDELanguage` for web files; tab size 2, LSP formatter/completion hooks, no
  Android-specific infra and no new native dependencies.
- New `WebLanguageProvider` maps `html/htm/css/js/mjs/cjs/ts/tsx/jsx/vue/md`
  to a `WebLanguage`; wired into `IDEEditor.createLanguage` as the fallback for
  non-tree-sitter files (was `EmptyLanguage`).
- JSON keeps its tree-sitter `JsonLanguage`; log files keep `LogLanguage`.

## 6. Android user-project references — classification

| Item | Class | Disposition |
|---|---|---|
| `ai/context/*` Android manifest/Gradle/sdk parsing | REMOVE | replaced by web detector/analyzer |
| `knowledge/IncrementalIndexer` java/kt-only | REMOVE | web extensions |
| `knowledge/FileParser` stub | REMOVE | real web parser |
| `models/NewProjectDetails.java` (minSdk/targetSdk template model, unused) | REMOVE | deleted |
| `models/FileExtension` JAVA/KT/XML/GRADLE/APK/CPP/BAT/GRADLEW entries | REMOVE | replaced with HTML/CSS/JS/TS/MD |
| `SaveResult.gradleSaved` / `saveAll` gradle flag | REFACTOR (deferred) | leftover naming in VibeIDE's own save flow; harmless |
| `IdeBuildVerification.kt`, `AndroidUtils.java`, `IntentUtils`/`crashReportingPrefExts` `context.packageName` | KEEP | VibeIDE's own Android app shell |
| tree-sitter grammars for Java/Kotlin/XML/Cpp/Groovy | THIRD_PARTY (already removed) | no grammars for web languages yet (see remaining work) |
| `com.hmx.ide.*` build-logic/desugaring/plugin IDs | KEEP | build infrastructure |

VibeIDE's own Android build system (Gradle wrapper, AGP, manifest, resources,
dependencies) is untouched.

## 7. Preview integration

`WebPreviewServer` serves from `IProjectManager.projectDir` (project root), no
hardcoded `app/src/main`. Verified `PreviewProjectAction` passes the project
directory directly.

## 8. Tests added

- `core/projects/src/test/.../WebProjectTest.kt` — WebProject create/open/root/
  entry/metadata; workspace open/close/get/getProjects/active; multiple
  subprojects; `getProject` not-found exception.
- `core/app/src/test/.../WebProjectDetectorTest.kt` — detection for index.html,
  package.json (node), vite, react, unknown; package.json dep parsing.
- `core/app/src/test/.../ProjectAnalyzerTest.kt` — web-oriented context;
  JS import/declaration extraction; node_modules/dist skipping; context-cache
  staleness on `index.html` change.

## 9. Workspace model

`IWorkspace`/`IProjectManager`/`WebProject` were already generic web models
(no Gradle, no modules). `openProject` = workspace configuration,
`destroy` = close, `activeFile` = active document, `subProjects` = multiple
projects. Covered by the new tests.