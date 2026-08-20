# VIBEIDE_REMOVALS.md

Live ledger of everything removed from the AndroidIDE fork while migrating to
VibeIDE. This is the executable checklist counterpart to
`VIBEIDE_MODULE_MIGRATION.md`. It is updated as removals are applied.

Every entry: **what** / **why** / **status**.

Status: `PLANNED` → `DONE` (or `SKIPPED` with reason).

---

## 1. Gradle modules to delete from `settings.gradle.kts` + disk

| # | Module | Why | Status |
|---|---|---|---|
| 1 | `:tooling:api` | User-project Gradle tooling (JSON-RPC, IProject proxy) | DONE |
| 2 | `:tooling:model` | AGP model RPC interfaces | DONE |
| 3 | `:tooling:builder-model-impl` | AGP v2 builder-model implementations | DONE |
| 4 | `:tooling:events` | build progress events | DONE |
| 5 | `:tooling:impl` | on-device Gradle tooling server (shadow jar asset) | DONE |
| 6 | `:tooling:plugin` | Gradle plugins applied to user projects (`com.hmx.ide`, `.init`, `.logsender`) | DONE |
| 7 | `:tooling:plugin-config` | plugin/app shared constants | DONE |
| 8 | `:java:javac-services` | on-device javac for editing user Java | DONE |
| 9 | `:java:lsp` | Java language server | DONE |
| 10 | `:xml:aaptcompiler` | AAPT2 resource-table compiler | DONE |
| 11 | `:xml:dom` | LemMinX XML DOM parser | DONE |
| 12 | `:xml:lsp` | Android XML language server | DONE |
| 13 | `:xml:resources-api` | AAPT2 resource types | DONE |
| 14 | `:xml:utils` | Android XML knowledge registries | DONE |
| 15 | `:utilities:uidesigner` | Android XML drag-drop layout designer | DONE |
| 16 | `:utilities:xml-inflater` | layout inflater for the designer | DONE |
| 17 | `:utilities:templates-api` | Android project template engine | REPLACED (web templates planned) |
| 18 | `:utilities:templates-impl` | Android activity templates (8) | REPLACED (web templates planned) |
| 19 | `:annotation:annotations` | `ViewAdapter`/`IncludeInDesigner` (designer-only) | DONE |
| 20 | `:annotation:processors-ksp` | KSP inflater adapter index | DONE |
| 21 | `:logging:logsender` | logs from user-built Android apps | DONE |
| 22 | `:logging:logsender-sample` | sample for logsender | DONE |
| 23 | `:testing:gradleToolingTest` | tests for removed tooling server | DONE |
| 24 | `:testing:benchmarks` | androidx benchmarks of Java LSP | DONE |
| 25 | `:editor:lexers` | ANTLR grammars (java/kotlin/xml/cpp/groovy) | DONE |

## 2. Composite-build modules

| # | Component | Why | Status |
|---|---|---|---|
| 1 | `build-deps:google-java-format` | Java LSP formatter | DONE |
| 2 | `build-deps:java-compiler` | tooling/javac path | DONE |
| 3 | `build-deps:javac` | javac-services | DONE |
| 4 | `build-deps:javapoet` | Java class generation (templates/java lsp/ClassBuilder) | DONE |
| 5 | `build-deps:jaxp` | XML DOM/resource tooling | DONE |
| 6 | `build-deps:jdk-compiler` | javac-services | DONE |
| 7 | `build-deps:jdk-jdeps` | javac-services | DONE |
| 8 | `build-deps:jdt` | Java LSP + XML utils | DONE |
| 9 | `build-deps:layoutlib-api` | XML resource tooling + designer | DONE |

Kept: `appintro`, `fuzzysearch`, `logback-core`, `desugaring-core`,
`external/logback-android`.

## 3. Build-logic tasks/plugins

| # | Component | Why | Status |
|---|---|---|---|
| 1 | `AndroidIDEAssetsPlugin` | packages `tooling-api-all.jar` into app assets | DONE |
| 2 | `GenerateInitScriptTask` | generates init.gradle injecting tooling plugin | DONE (with plugin + tasks) |
| 3 | `SetupAapt2Task` | downloads AAPT2 for user builds | DONE |

## 4. App (`core:app`) feature removal

| # | Package / file | Why | Status |
|---|---|---|---|
| 1 | `services/builder/*` (GradleBuildService, ToolingServerRunner, etc.) | on-device Gradle builds | DONE |
| 2 | `services/ToolingServer*`, `services/log/LogReceiverService*` | tooling daemon + log forwarding | DONE |
| 3 | `actions/build/*` (ProjectSyncAction…) | sync user projects | DONE |
| 4 | `build/*` (RemoteBuildManager, GitHubBuildClient, RemoteBuildActivity, BuildTools APK functions, ProjectGitInfo) | remote GitHub APK build | DONE |
| 5 | `viewmodel/BuildVariantsViewModel`, `RunTasksViewModel` + layouts/adapters | Android variants / Gradle tasks | DONE |
| 6 | `preferences/buildAndRunPrefExts.kt`, `xmlPrefExts.kt`, `javaPrefExts.kt` | Android build/XML/Java prefs | DONE |
| 7 | `LspHandler` Java+XML server registration | Java/XML language servers | DONE |
| 8 | `actions/etc/PreviewLayoutAction` | opens UI designer | DONE |
| 9 | Template wizard: `TemplateListFragment`, `TemplateDetailsFragment`, `NewProjectDetails`, `ProjectTemplate`, `TemplateRecipeExecutor`, `ProjectWriter`, `ClassBuilder`, template layouts | Android project wizard | DONE (replaced by web template + AI flows) |
| 10 | `utils/JdkUtils`, `app/configuration/JdkDistributionProviderImpl`, `IDEBuildConfigProviderImpl` (ABI check) | JDK/SDK management for user builds | DONE |
| 11 | `IDEApplication` BuildTools/GitHub init + `StatUploadWorker` keep? | remote-build init; stats kept (opt-in) | DONE (BuildTools init removed; stats kept) |
| 12 | `NewFileAction` Java/Android-res creation (Java class/Activity, layout/menu/drawable wizards) | Android-only; now plain file creator | DONE (uses javapoet + `jdkx` stub → dangling imports) |
| 13 | `ProjectWriter.java`, `ClassBuilder.kt`, `ProjectWriterCallback.java`, `JdkDistribution.kt`, `layout_create_file_java.xml` | javapoet class generation, dead after 12 | DONE |
| 14 | pref dangling refs: `BuildAndRunPreferences` (rootPrefExts), `JavaCodeConfigurations`/`XMLPreferencesScreen` (editorPrefExts) | pointed at deleted pref screens | DONE (lines removed) |

## 5. `core:projects` removal

| # | Item | Why | Status |
|---|---|---|---|
| 1 | `GradleProject`, `ProjectType`, buildScript/tasks | Gradle model | DONE |
| 2 | `ModuleProject`, `CachingProject` classpaths, source trie, boot classpaths | Java/Gradle analysis | DONE |
| 3 | `AndroidModule`, `JavaModule` | Android/Java module models | DONE |
| 4 | `WorkspaceModelBuilder` (tooling proxy → workspace) | Gradle sync | DONE |
| 5 | `classpath/*` (JarFs/ZipFile classpath readers) | classpath indexing | DONE |
| 6 | `util/BootClasspathProvider` | android.jar boot classpath | DONE |

Kept/adapted: `IProjectManager`/`ProjectManagerImpl`, `IWorkspace`/`WorkspaceImpl`,
`FileManager`, `ActiveDocument`, event-driven file change handling → web project model
(`WebProject`, root path `:`, `openProject(File)` + `ProjectInitializedEvent`).

## 6. `core:common` removal

| # | Item | Why | Status |
|---|---|---|---|
| 1 | `Environment.java` Android constants (ANDROID_HOME, JAVA_HOME, AAPT2, ANDROID_JAR, INIT_SCRIPT) | user-project Android toolchain | DONE |
| 2 | `IdeShellEnvironment` Gradle/JDK env setup | tooling daemon env | DONE (with `shell/` package) |
| 3 | `IJdkDistributionProvider`, `IDEBuildConfigProvider` | JDK/device support | DONE (app `configuration/` package + interfaces) |
| 4 | `managers/ToolsManager` tooling-jar extraction | tooling asset | DONE |
| 5 | legacy `syntax/highlighters/JavaHighlighter` | Java highlighting (ANTLR) | DONE |

Kept: themes, generic syntax schemes, base activity classes, memory watcher.
`Environment` retained fields: ROOT, HOME, ANDROIDIDE_HOME, ANDROIDIDE_UI, PROJECTS_DIR,
`getProjectCacheDir`. `FileProvider` (test helper) + `testing/resources` removed.

## 7. `editor/impl` removal

| # | Item | Why | Status |
|---|---|---|---|
| 1 | `JavaLanguage`, `KotlinLanguage`, `XMLLanguage` + tree-sitter registrations | Android-era languages | DONE |
| 2 | `CppLanguage`/`GroovyLanguage` + ANTLR fallback | not web | DONE (with `language/cpp`, `language/groovy`, `language/incremental`) |
| 3 | tree-sitter assets `editor/treesitter/{java,kt,xml}/` | grammar assets | DONE |
| 4 | scheme files `java.json`, `kotlin.json`, `xml.json` | language schemes | DONE |

Kept: `JsonLanguage`, `LogLanguage`, editor core, completion UI, snippets, schemes engine,
`language/newline`, `TreeSitterLanguage`.

## 8. Manifest / permissions

| # | Item | Why | Status |
|---|---|---|---|
| 1 | `MANAGE_EXTERNAL_STORAGE` | all-files access for Gradle/SAF; re-evaluate | DONE |
| 2 | `FOREGROUND_SERVICE` | foreground services (build?) — remove if unused | DONE |
| 3 | `LogReceiverService` + `BIND_LOG_SERVICE` permission | log forwarding | DONE (incl. permlab/permdesc strings + bool) |
| 4 | `SYSTEM_ALERT_WINDOW` | crash overlay — keep if crash UI kept | REVIEW |

Kept: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (crash/AI),
storage via SAF (`IDEDocumentsProvider`), `READ/WRITE_EXTERNAL_STORAGE` if
file access retained.

## 9. Resources tied to removed features

DONE: `fragment_build_variants.xml`, `layout_build_variant_item.xml`,
`layout_run_task*.xml`, `layout_template_list_item.xml`,
`layout_template_widgetlist_item.xml`, `template_*` strings,
`title_run_tasks`/`hint_search_tasks`/`msg_err_select_tasks`/`msg_tasks_to_run`,
`title_build_variants`/`msg_build_variants_fetch_failed`, LogSender strings + `bools.xml`.
Translations (values-*/strings.xml) still carry orphaned LogSender/template strings — harmless.

Toml cleanup: `antlr4`, `common-antlr4`, `common-antlr4-runtime`, `common-jsonrpc`,
`gradle-tooling`, `ksp` version aliases removed. `common-javapoet` retained (annotation
processors still use it).

## 10. Verification

- After each checkpoint run `./gradlew assembleDebug` (when an SDK is available)
  and fix compile errors before continuing.
- Grep for dangling references to removed modules: `:tooling`, `:java:`,
  `:xml:`, `uidesigner`, `xmlInflater`, `templates`, `logsender`, `ToolingApi`,
  `GradleBuildService`, `PreviewLayoutAction`, `Environment.AAPT2`, etc.

## 11. Post-surgery static pass (2026-08)

Full import-resolution sweep of every surviving `com.hmx.ide.*` import across all
modules (incl. top-level funs/props, Java file facades, generated R/databinding)
found and fixed these real dangling references:

| # | Fix | Why |
|---|---|---|
| 1 | `runOnUiThread`/`cancelIfActive` recreated in `core/common/.../tasks/Tasks.kt` | helpers imported by core/app, core/common, editor/impl but their source file had been deleted |
| 2 | `NewFileAction` → plain file creator | used javapoet + `jdkx` stub + deleted `LayoutCreateFileJavaBinding`; Java/Android-res wizards removed |
| 3 | Deleted `ProjectWriter.java`, `ClassBuilder.kt`, `ProjectWriterCallback.java`, `JdkDistribution.kt`, `layout_create_file_java.xml` | dead javapoet class generation |
| 4 | `CommonCompletionProvider`: removed `setupLookupForCompletion` call+import | was defined in deleted `lsp/java`/`lsp/kotlin` |
| 5 | `StringSearch.packageName`: dropped `ActiveJavaDocument` branch | class deleted from `core:projects:models` |
| 6 | `rootPrefExts`/`editorPrefExts`: removed refs to `BuildAndRunPreferences`, `JavaCodeConfigurations`, `XMLPreferencesScreen` | pref screens deleted with prefExts |
| 7 | Deleted orphan `activity_remote_build.xml`; stripped `tools:showIn="@layout/layout_run_tasks_category"` from `layout_divider_horizontal.xml` | layouts for deleted RemoteBuildActivity / run-tasks |
| 8 | Removed orphan strings: `title_remote_build`, `title_preview_layout`, `msg_emptyview_applogs`, `err_selected_variant_not_found`, `msg_experimental_flavor`, `restype_*`, `title_choose_application` | unused after feature removal |

Note: bash tool output redacts some identifiers (shown as `n`); verify files with
Read/grep tools, not trust mangled shell output.