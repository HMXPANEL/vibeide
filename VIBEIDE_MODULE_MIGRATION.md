# VIBEIDE_MODULE_MIGRATION.md

Module-by-module classification of the `hmx-ide` codebase (fork of
[AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE)) as part of the
migration from an Android IDE into **VibeIDE**, a mobile-first AI vibe-coding
IDE for **web applications**.

Legend: **KEEP** (generic infra, reuse as-is) · **ADAPT** (keep, change scope /
decouple from Android) · **REMOVE** (Android-user-project-only, delete) ·
**REPLACE** (rebuild for web) · **UNKNOWN** (investigate before touching).

---

## 1. Module inventory & classification

### Annotation processors — `annotation/`

| Field | Value |
|---|---|
| **Module** | `:annotation:annotations` |
| Purpose | `ViewAdapter` + `IncludeInDesigner` annotations (xml-inflater / UI designer). |
| Current dependencies | kotlin, androidx annotation |
| Used by | `annotation:processors`, `annotation:processors-ksp`, `editor:impl`, `utilities:xml-inflater`, `utilities:uidesigner` |
| VibeIDE relevance | None once UI designer is removed. |
| Decision | **REMOVE** |
| Reason | The only content serves the Android XML layout designer (`IncludeInDesigner`) and the layout inflater (`ViewAdapter`). |
| Dependencies that must change | Remove from `editor:impl`, delete `processors-ksp`. |
| Replacement | None. |
| Risk | Low (confirmed all consumers are designer/inflater only). |

| Field | Value |
|---|---|
| **Module** | `:annotation:processors` |
| Purpose | EventBus `*EventsIndex` subscriber-index generator (kapt). |
| Current dependencies | eventbus, javapoet, auto-service, guava |
| Used by | core:projects, core:lsp-api, editor:impl, java:lsp, xml:lsp, core:app |
| VibeIDE relevance | High — generic event dispatch infra. |
| Decision | **KEEP** |
| Reason | EventBus indexes are required by retained modules. |
| Risk | Low. |

| Field | Value |
|---|---|
| **Module** | `:annotation:processors-ksp` |
| Purpose | KSP `ViewAdapterSymbolProcessor` (generates inflater adapter index). |
| Current dependencies | ksp, annotation:annotations |
| Used by | `editor:impl`, `utilities:xml-inflater` |
| VibeIDE relevance | None. |
| Decision | **REMOVE** |
| Reason | Serves only the removed layout-inflater. |
| Risk | Low. |

### Core — `core/`

| Field | Value |
|---|---|
| **Module** | `:core:app` |
| Purpose | The application shell: activities, fragments, editor wiring, AI stack, remote build, git. |
| Current dependencies | nearly every module (see `core/app/build.gradle.kts`) |
| Used by | — (top-level app) |
| VibeIDE relevance | **Core of the product.** |
| Decision | **ADAPT** |
| Reason | Remove Android build/sync (services/builder, actions/build, BuildVariants/RunTasks VMs, RemoteBuild UI, buildAndRun/Xml/Java pref screens, Java/XML LSP registration, preview-layout action, template wizard). Keep editor, file explorer, AI chat, git, preferences, crash handling, onboarding. |
| Dependencies that must change | Drop tooling:*, java:*, xml:*, utilities:uidesigner, xml-inflater, templates-*, logsender. |
| Risk | High — largest surgical surface. |

| Field | Value |
|---|---|
| **Module** | `:core:common` |
| Purpose | Shared utils: process builder, shell env, themes, color schemes, legacy JavaHighlighter, `Environment.java` (Android toolchain paths), base activities. |
| Current dependencies | editor:lexers, core:resources, event:eventbus*, logging:logger, utilities:build-info/flashbar/shared, sora editor, androidx, guava, utilcode |
| Used by | Almost everything. |
| VibeIDE relevance | High (process execution, themes, base classes). |
| Decision | **ADAPT** |
| Reason | Keep generic parts (`IProcessBuilder`, themes, syntax schemes). Remove/neutralize Android-toolchain-only parts: `Environment.java` constants for ANDROID_HOME/JAVA_HOME/AAPT2/INIT_SCRIPT, `IdeShellEnvironment` Gradle/JDK setup, `IJdkDistributionProvider`, `IDEBuildConfigProviderImpl` (ABI support check). `JavaHighlighter` is legacy (ANTLR) — keep only if `editor:lexers` java grammar is kept; otherwise delete. |
| Risk | Medium. |

| Field | Value |
|---|---|
| **Module** | `:core:actions` |
| Purpose | Editor/sidebar action registry system (toolbar items, menus) on androidx Navigation + AutoService. |
| Current dependencies | core:common, core:resources, sora, androidx nav, utilcode |
| Used by | core:app, editor:impl, lsp modules, uidesigner |
| VibeIDE relevance | High — generic action/menu infra. |
| Decision | **KEEP** |
| Reason | The action registry is how the editor toolbar, file-tree ops, and future AI actions are wired. |
| Risk | Low. |

| Field | Value |
|---|---|
| **Module** | `:core:indexing-api` |
| Purpose | Minimal indexing contract (`SymbolLocation`). |
| Current dependencies | core:common, core:projects, logging:logger, utilities:shared |
| VibeIDE relevance | Medium (AI knowledge/context). |
| Decision | **KEEP** (decouple from core:projects) |
| Reason | Generic model used by knowledge-api and the AI context engine. |
| Risk | Low. |

| Field | Value |
|---|---|
| **Module** | `:core:knowledge-api` |
| Purpose | `KnowledgeEngine` interface + project/module/file/symbol model. |
| Current dependencies | core:common, core:projects, core:indexing-api |
| VibeIDE relevance | High — AI context. |
| Decision | **ADAPT** |
| Reason | Interface is generic; the `core:app` implementation (`KnowledgeEngineImpl`, `JavaAstWalker`, `FileParser`) is Java-AST-specific and must be generalized to web files (plain text/tree-sitter). |
| Risk | Low (interface) / Medium (impl). |

| Field | Value |
|---|---|
| **Module** | `:core:lsp-api` |
| Purpose | IDE LSP abstraction: `ILanguageServer`, `ILanguageClient`, registry, completions, snippets, server settings. |
| Current dependencies | core:projects (api), core:lsp-models (api), utilities:lookup/preferences (api), **xml:utils (api)**, event:eventbus-events, fuzzysearch, sora, androidx, utilcode |
| VibeIDE relevance | **High** — the editor→language-tooling bridge for HTML/CSS/JS/TS servers. |
| Decision | **ADAPT** |
| Reason | Generic LSP core, but coupled to Android via `api(projects.core.projects)` and `api(projects.xml.utils)`. Decouple both (move the couple of XML-completion utilities into the XML layer or drop them) so future web language servers are Android-free. |
| Risk | Medium. |

| Field | Value |
|---|---|
| **Module** | `:core:lsp-models` |
| Purpose | Pure LSP data models (completion, diagnostics, definitions, edits, text documents…). |
| Current dependencies | core:common, sora, fuzzysearch, utilcode |
| VibeIDE relevance | **High** — generic. |
| Decision | **KEEP** |
| Reason | Framework-neutral LSP types; will back HTML/CSS/JS/TS servers. |
| Risk | Low. |

| Field | Value |
|---|---|
| **Module** | `:core:projects` |
| Purpose | Project model: `IProjectManager`, `IWorkspace`, `GradleProject`, `ModuleProject`/`AndroidModule`/`JavaModule`, classpaths, boot classpaths, model builder from Gradle tooling. |
| Current dependencies | event:eventbus*, **tooling:api**, core:common, **java:javac-services**, logging:logger, utilities:lookup/shared, **xml:utils**, guava, io, coroutines |
| VibeIDE relevance | Medium — the *workspace/project* concept is essential. |
| Decision | **REPLACE** (core concepts) |
| Reason | Keep `IProjectManager`, `IWorkspace`, `FileManager`, `CachingProject`, `ActiveDocument`; delete `GradleProject`/`ModuleProject`/`AndroidModule`/`JavaModule`, classpath indexing, boot classpaths, and the `WorkspaceModelBuilder` that reads the Gradle tooling model. Replace with a plain web-project model: name, path, files, folders, active file, settings, preview config, AI context. |
| Dependencies that must change | Drop tooling:api, java:javac-services, xml:utils. |
| Risk | High. |

| Field | Value |
|---|---|
| **Module** | `:core:resources` |
| Purpose | The Android resource bundle for the IDE itself: drawables, strings (12 locales), fonts, themes, mipmaps, `LocaleProvider`. |
| Current dependencies | appcompat, preference, splashscreen, material |
| Used by | core:common, core:actions, core:app, editor:impl, utilities:*, java:lsp, logging:build-info |
| VibeIDE relevance | **High** — the VibeIDE app UI strings/drawables/themes. |
| Decision | **ADAPT** (rebrand & redesign later) |
| Reason | This is the IDE's *own* resources, not user-project resources. Keep during migration; replace branding/design in the redesign phase. |
| Risk | Low. |

### Editor — `editor/`

| Field | Value |
|---|---|
| **Module** | `:editor:api` |
| Purpose | `IEditor`, `ILspEditor` contracts + tree-sitter JNI wrapper API. |
| Current dependencies | androidide-ts (native), core:lsp-api, core:lsp-models, core:common, logging:logger |
| VibeIDE relevance | **High** — editor is core. |
| Decision | **KEEP** |
| Reason | Generic editor + LSP editor contracts. |
| Risk | Low. |

| Field | Value |
|---|---|
| **Module** | `:editor:impl` |
| Purpose | sora-editor integration: `IDEEditor`, language implementations, color schemes, completion UI, snippets, tree-sitter languages (Java/Kotlin/XML/JSON/Log), C++/Groovy fallback. |
| Current dependencies | tree-sitter grammars (java/json/kotlin/log/xml), sora editor, editor:api/treesitter/lexers, core:actions/common/lsp-api/resources, event:eventbus*, **java:lsp**, **xml:lsp**, annotation:annotations, utilities:shared |
| VibeIDE relevance | **High** — the editor. |
| Decision | **ADAPT** |
| Reason | Keep `IDEEditor`, scheme machinery, completion UI, `JsonLanguage`, `LogLanguage`. Remove `JavaLanguage`, `KotlinLanguage`, `XMLLanguage`, `CppLanguage`, `GroovyLanguage`, tree-sitter java/kt/xml assets, and the `java:lsp`/`xml:lsp` wiring. Add HTML/CSS/JS/TS languages later (tree-sitter grammars: html, css, javascript, typescript). |
| Dependencies that must change | Drop java:lsp, xml:lsp, annotation:annotations, java/kt/xml/cpp/groovy grammar deps. |
| Risk | High (language registration & scheme assets). |

| Field | Value |
|---|---|
| **Module** | `:editor:lexers` |
| Purpose | ANTLR4 grammars + generated lexers (java, kotlin, xml, cpp, groovy, javadoc). |
| Current dependencies | antlr4-runtime, kotlin |
| Used by | core:common (legacy JavaHighlighter), core:app, xml:lsp, editor:impl |
| VibeIDE relevance | Low. |
| Decision | **ADAPT / REMOVE** |
| Reason | The ANTLR grammars only serve Android-era languages (Java/Kotlin/XML/C++/Groovy). If legacy `JavaHighlighter` is dropped, the module has no consumers → remove. If kept temporarily, trim to nothing but the module. Prefer **REMOVE** once `JavaHighlighter`/Groovy/Cpp are gone. |
| Risk | Medium (verify `JavaHighlighter` consumers first). |

| Field | Value |
|---|---|
| **Module** | `:editor:treesitter` |
| Purpose | Vendored/adapted sora-editor tree-sitter engine (analyze manager, spans, theme, predicates). |
| Current dependencies | sora editor, coroutines, androidide-ts, androidx collection, core:common, editor:api, logging:logger |
| VibeIDE relevance | **High** — syntax highlighting for web languages. |
| Decision | **KEEP** |
| Reason | Language-agnostic tree-sitter integration; the web grammars plug in at the `editor:impl` level. |
| Risk | Low. |

### Events — `event/`

| Field | Value |
|---|---|
| **Module** | `:event:eventbus` |
| Purpose | GreenRobot EventBus fork (pure JVM). |
| Decision | **KEEP** — generic event dispatch. |
| **Module** | `:event:eventbus-android` |
| Purpose | Android main-thread poster. |
| Decision | **KEEP** — required by Android app. |
| **Module** | `:event:eventbus-events` |
| Purpose | Event POJOs (document, file, filetree, project, preferences). |
| Decision | **ADAPT** — keep generic events; remove Java/XML-specific ones if any remain after language removal. |

### Java language tooling — `java/`

| Field | Value |
|---|---|
| **Module** | `:java:javac-services` |
| Purpose | On-device reusable javac compiler (fork of OpenJDK javac) for editing user Java. |
| Current dependencies | composite javac, kotlin, core:common, logging:logger, utilcode, guava |
| Used by | java:lsp, core:projects, core:app |
| VibeIDE relevance | None. |
| Decision | **REMOVE** |
| Reason | Java editing is out of scope; web languages replace it. |
| Risk | Medium (consumer cleanup in core:projects + core:app). |

| Field | Value |
|---|---|
| **Module** | `:java:lsp` |
| Purpose | The Java language server (completion, diagnostics, refactors, format). |
| Current dependencies | javac-services, tree-sitter java, javaparser, javapoet, google-java-format, jdt, core:indexing-api/lsp-api/resources/actions/common, editor:api, utilcode |
| Used by | editor:impl, core:app |
| VibeIDE relevance | None. |
| Decision | **REMOVE** |
| Reason | Java language intelligence is not needed by a web IDE. |
| Risk | Medium. |

### Logging — `logging/`

| Field | Value |
|---|---|
| **Module** | `:logging:logger` |
| Purpose | Logback-based logging (logcat/JVM appenders). |
| Decision | **KEEP** — generic app logging. |
| **Module** | `:logging:idestats` |
| Purpose | Anonymous usage stats (WorkManager + Retrofit). |
| Decision | **KEEP** (opt-in; generic) — revisit in privacy pass. |
| **Module** | `:logging:logsender` |
| Purpose | Log-forwarding compiled into *user Android apps* (socket) + app-side receive. |
| Decision | **REMOVE** — exists only to stream logs out of user-built Android apps. |
| **Module** | `:logging:logsender-sample` |
| Purpose | Sample app for logsender. |
| Decision | **REMOVE**. |

### Testing — `testing/`

| Field | Value |
|---|---|
| **Module** | `:testing:androidTest` |
| Purpose | Shared androidTest infra. |
| Decision | **KEEP** (trim to retained features). |
| **Module** | `:testing:benchmarks` |
| Purpose | androidx.benchmark macrobenchmarks (Java LSP). |
| Decision | **REMOVE** — benchmarks target Java LSP / removed features. |
| **Module** | `:testing:commonTest` |
| Purpose | Shared test helpers. |
| Decision | **ADAPT** — drop tooling-test helpers. |
| **Module** | `:testing:gradleToolingTest` |
| Purpose | Gradle tooling API integration tests. |
| Decision | **REMOVE** — tests the removed tooling server. |
| **Module** | `:testing:lspTest` |
| Purpose | LSP integration tests (Java/XML). |
| Decision | **ADAPT** — drop Java/XML test assets; keep harness for future web language servers. |
| **Module** | `:testing:unitTest` |
| Purpose | Shared unit-test infra. |
| Decision | **KEEP**. |

### Tooling (user-project Gradle build) — `tooling/`

All seven modules exist to sync and build **user Android projects** with the
on-device Gradle tooling server. None of this functionality survives in VibeIDE.

| Field | Value |
|---|---|
| **Module** | `:tooling:api` | JSON-RPC contracts, `IProject` proxy, message types. | **REMOVE** |
| **Module** | `:tooling:model` | RPC model interfaces (`IAndroidProject`, variants, artifacts). | **REMOVE** |
| **Module** | `:tooling:builder-model-impl` | AGP `com.android.builder.model.v2.*` implementations. | **REMOVE** |
| **Module** | `:tooling:events` | Build progress event classes. | **REMOVE** |
| **Module** | `:tooling:impl` | Tooling server (`ToolingApiServerImpl`, model builders), shadow-jar asset. | **REMOVE** |
| **Module** | `:tooling:plugin` | Gradle plugins applied to user projects (`com.hmx.ide`, `com.hmx.ide.init`, `com.hmx.ide.logsender`). | **REMOVE** |
| **Module** | `:tooling:plugin-config` | Shared constants between app + plugin. | **REMOVE** |

Reason: these build/sync user Android projects via Gradle+AGP (Gradle tooling
server, init script, model builders, AAPT2 override, LogSender injection).
VibeIDE does not build user Android projects. Removing the group also removes
`tooling-api-all.jar` packaging, init-script generation, and AAPT2 setup.

Risk: **High** — every reference in core:app, core:projects, build-logic
(`AndroidIDEAssetsPlugin`, `GenerateInitScriptTask`, `SetupAapt2Task`),
`core/common` (`ToolsManager`, `Environment`, `IdeShellEnvironment`) must go.

### Utilities — `utilities/`

| Field | Value |
|---|---|
| **Module** | `:utilities:build-info` | Build-config constants for the IDE's own build. | **KEEP** |
| **Module** | `:utilities:flashbar` | Vendored Flashbar toast library. | **KEEP** |
| **Module** | `:utilities:framework-stubs` | Compile-only android.jar stubs. | **KEEP** (build infra) |
| **Module** | `:utilities:lookup` | JVM service registry. | **KEEP** |
| **Module** | `:utilities:preferences` | SharedPreferences wrappers. | **ADAPT** — drop `BuildPreferences`, `JavaPreferences`, `XmlPreferences`; keep editor/general/AI/devops/stats. |
| **Module** | `:utilities:shared` | Pure-JVM utils (ServiceLoader, ClassTrie, StopWatch…). | **KEEP** |
| **Module** | `:utilities:templates-api` | Android project-template engine (recipe executor, `ProjectTemplateBuilder`, `AndroidModuleTemplateBuilder`, manifest/settings/buildscript writers). | **REPLACE** — keep the *recipe/parameter* concept; replace builders with web templates. |
| **Module** | `:utilities:templates-impl` | 8 Android activity templates (bottomNav, navDrawer, tabbed, compose…). | **REPLACE** — replace with web templates: Blank Web App, Landing Page, Portfolio, Blog, Dashboard, E-commerce, SaaS, Movie Website. |
| **Module** | `:utilities:treeview` | Vendored Android tree-view (file explorer). | **KEEP** |
| **Module** | `:utilities:uidesigner` | Android XML drag-drop layout designer (`UIDesignerActivity`, palette, attrs, undo). | **REMOVE** — replaced later by Web Preview. |
| **Module** | `:utilities:xml-inflater` | Inflates Android layout XML without compilation. | **REMOVE** — rendering engine for the removed designer. |

### XML tooling (Android XML) — `xml/`

All of `xml/*` supports editing/inflating **Android XML layouts, manifests and
resources** for user Android projects. VibeIDE replaces this with web HTML/CSS
support (a future `web/` group with tree-sitter HTML/CSS/JS grammars and a web
preview runtime).

| Field | Value |
|---|---|
| **Module** | `:xml:aaptcompiler` | AAPT2 resource-table compiler port (in-IDE completion + designer). | **REMOVE** |
| **Module** | `:xml:dom` | LemMinX-derived XML DOM parser + formatter. | **REMOVE** |
| **Module** | `:xml:lsp` | Android XML language server (manifest/layout completions). | **REMOVE** |
| **Module** | `:xml:resources-api` | AAPT2 resource types, `IResourceTable` interfaces. | **REMOVE** |
| **Module** | `:xml:utils` | Resource/Widget/ApiVersion registries powering XML completion. | **REMOVE** |

---

## 2. Composite builds

| Composite | Module | Decision | Notes |
|---|---|---|---|
| `build-logic` | plugins, common, desugaring, properties-parser | **ADAPT** | Remove `AndroidIDEAssetsPlugin` (tooling jar asset), `GenerateInitScriptTask`, `SetupAapt2Task`; keep module config, publishing, properties-parser. |
| `build-deps` | `appintro` | **KEEP** | Onboarding wizard. |
| `build-deps` | `fuzzysearch` | **KEEP** | Fuzzy matching for completions/finder. |
| `build-deps` | `google-java-format` | **REMOVE** | Only used by `java:lsp`. |
| `build-deps` | `java-compiler` | **REMOVE** (verify) | Used by tooling/javac path. |
| `build-deps` | `javac` | **REMOVE** | Only used by `java:javac-services`. |
| `build-deps` | `javapoet` | **REMOVE** (verify) | Used by `java:lsp`, `templates-api`, `core:app` `ClassBuilder`. All removed. |
| `build-deps` | `jaxp` | **REMOVE** (verify) | Used by xml:dom, xml:resources-api, xml:aaptcompiler, templates-api. Verify no surviving consumer. |
| `build-deps` | `jdk-compiler` | **REMOVE** | javac-services. |
| `build-deps` | `jdk-jdeps` | **REMOVE** | javac-services. |
| `build-deps` | `jdt` | **REMOVE** | java:lsp, xml:utils. |
| `build-deps` | `layoutlib-api` | **REMOVE** | xml:resources-api, xml:aaptcompiler, uidesigner. |
| `build-deps` | `logback-core` | **KEEP** | Android-patched logback, used by `logging:logger`. |
| `build-deps-common` | `desugaring-core` | **KEEP** | Core desugaring. |
| `external` | `logback-android` (submodule) | **KEEP** | Source for patched logback-core. |

---

## 3. `gradle/libs.versions.toml` cleanup

Remove: agp-tooling, gradle-tooling, tree-sitter grammars for java/kotlin/xml
(keep `android-tree-sitter` base for future web grammars), logback? (keep),
aapt2-*, tooling-builderModel/gradleApi, composite googleJavaFormat/javac/
jdkCompiler/jdkJdeps/jdt/javapoet/jaxp/layoutlib, security-crypto (if
GitHubTokenStorage survives it stays), javaparser, google-java-format,
antlr4 (if lexers removed), benchmark, nav-safe-args (keep — app uses
navigation), realm (AI memory — keep), markwon (keep — used? verify),
appintro (keep).

---

## 4. File-level classification (core Android-IDE features)

**REMOVE in `core:app`** — services/builder/*, services/ToolingServer*,
`LogReceiverService`, `actions/build/*` (ProjectSyncAction…), `build/` (remote
GitHub APK build), `BuildVariantsViewModel/Adapter`, `RunTasksViewModel`,
`preferences/buildAndRunPrefExts.kt`, `preferences/xmlPrefExts.kt`,
`preferences/javaPrefExts.kt`, `LspHandler` Java/XML registration,
`PreviewLayoutAction`, template wizard (`TemplateListFragment`,
`TemplateDetailsFragment`, `NewProjectDetails`, `ProjectTemplate` model,
`TemplateRecipeExecutor`, `ProjectWriter`, `ClassBuilder`), `utils/JdkUtils`,
`app/configuration/JdkDistributionProviderImpl`,
`app/configuration/IDEBuildConfigProviderImpl`.

**KEEP/ADAPT in `core:app`** — `IDEApplication` (drop BuildTools init),
`MainActivity`/`MainFragment` (drop Android wizard actions, keep create/open/
clone), `EditorActivity*` chain (drop sync + build variants), `PreferencesActivity`,
`AboutActivity`, `AIModelsActivity`, `AIChatActivity`, `CodeEditorView`,
`FileTreeFragment` + file-tree actions, `EditorBottomSheet` (diagnostics/search),
`OpenProjectSheet`, SAF `IDEDocumentsProvider`, crash UI, onboarding.

**REMOVE in build-logic** — `AndroidIDEAssetsPlugin`, `GenerateInitScriptTask`,
`SetupAapt2Task`.

**REMOVE in core/common** — Android toolchain env (`Environment.java` Android
constants, `IdeShellEnvironment` Gradle/JDK setup), `IJdkDistributionProvider`,
`IDEBuildConfigProvider`, legacy `JavaHighlighter` (with lexers).

---

## 5. Migration order

1. **checkpoint-01-analysis** — this document set + baseline commit. *(current)*
2. **checkpoint-02-android-feature-removal** — delete `tooling/*`, `java/*`,
   `xml/*`, `utilities/{uidesigner,xml-inflater,templates-*}`,
   `annotation/processors-ksp`, `annotation/annotations`, `logging/{logsender,
   logsender-sample}`, `testing/{gradleToolingTest,benchmarks}`; strip
   composite-builds; update `settings.gradle.kts`, root/`core:app` build files,
   `libs.versions.toml`, build-logic; strip `core:app` Android feature code;
   strip `core/projects` to the web project model.
3. **checkpoint-03-module-cleanup** — remove dead resources/strings/menus/
   drawables tied to removed features; clean `AndroidManifest.xml` permissions
   (FOREGROUND_SERVICE, MANAGE_EXTERNAL_STORAGE if SAF-only flow kept,
   SYSTEM_ALERT_WINDOW for crash overlay).
4. **checkpoint-04-vibeide-shell** — introduce web project model, web templates,
   HTML/CSS/JS editor languages, live web preview, VibeIDE branding.