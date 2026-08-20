# VibeIDE Package Migration Report

Application package renamed from `com.hmx.ide` to `com.hmx.webide`.

## Scope

- **Source roots migrated**: `core/*`, `editor/*`, `utilities/*`, `logging/*`,
  `event/*`, `annotation/*` — all Kotlin/Java sources, XML resources, layouts,
  Android manifest, proguard files, and documentation.
- **173 source directories** moved under `com/hmx/webide/**`.
- **593 files** content-replaced (`com.hmx.ide` -> `com.hmx.webide`).
- **60 files skipped** by the content replace (build files, build-logic,
  vendored code) — verified that their remaining `com.hmx.ide` references are
  exclusively build-infrastructure references (see below).
- Package-dir consistency check: **0 mismatches** between declared `package`
  and file path across all app source trees.

## applicationId / namespace

A single source of truth drives the app identity:
`composite-builds/build-logic/common/src/main/java/com/hmx/ide/build/config/BuildConfig.kt`

```
const val packageName = "com.hmx.webide"
```

This value now yields:
- `applicationId = com.hmx.webide`
- `namespace = com.hmx.webide` (core:app) and `com.hmx.webide.<sub>` for every
  app module (common, editor, resources, lsp-api, lsp-models, projects,
  knowledge-api, indexing-api, ...)
- kapt EventBus indices: `com.hmx.webide.events.AppEventsIndex`,
  `com.hmx.webide.events.EditorEventsIndex`, `com.hmx.webide.events.LspApiEventsIndex`,
  `com.hmx.webide.events.ProjectsApiEventsIndex` (all auto-derived from
  `BuildConfig.packageName`; `IDEApplication.kt` imports already updated).
- `BuildInfo.PACKAGE_NAME` (generated from `BuildInfo.java.in`, package
  `com.hmx.webide.buildinfo`).

Manifest: provider authority `com.hmx.webide.documents`; FileProvider authority
`${applicationId}.providers.fileprovider`.

## Deliberately preserved references (`com.hmx.ide.*`)

These are **build infrastructure or third-party/vendored code**, not the app
package, and must stay as-is:

| Location | Why kept |
|---|---|
| `composite-builds/build-logic/**` (plugins, common, desugaring, properties-parser) | Gradle build infrastructure. Plugin IDs `com.hmx.ide.build`, `com.hmx.ide.core-app`, `com.hmx.ide.build.propsparser`, `com.hmx.ide.desugaring`. |
| `composite-builds/build-deps-common/desugaring-core` | Vendored desugar runtime (`com.hmx.ide.desugaring.core`) referenced by the desugar plugin's `JavaIOReplacements`. |
| All `*.gradle.kts` / `libs.versions.toml` imports | Import `com.hmx.ide.build.config.*`, `com.hmx.ide.plugins.*`, `com.hmx.ide.desugaring.*` (build-logic). |
| Root `build.gradle.kts` group | Root project group `com.hmx.ide` (publishing coordinate). |
| Composite coordinates `com.hmx.ide.build:*` | `libs.versions.toml` + `settings.gradle.kts` substitute. |
| `io.github.rosemoe.sora.ts` (editor/treesitter) | Third-party namespace. |
| `com.unnamed.b.atv` (treeview) | Third-party namespace. |
| `com.github.appintro` | Third-party namespace. |

**Exception — renamed despite being inside composite-builds**:
`composite-builds/build-deps/fuzzysearch` (`com.hmx.ide.fuzzysearch`,
`com.hmx.ide.diffutils` -> `com.hmx.webide.fuzzysearch`, `com.hmx.webide.diffutils`)
because app code imports it directly (`core/lsp-models` Completions.kt,
`core/lsp-api` StringUtils.kt).

## Remaining non-build references (documented, not hidden)

- `changelogs/v2.7.1-beta.md` — historical changelog; F-Droid URL updated to
  `com.hmx.webide/`.
- `scripts/run_instrumentation_tests.sh` — references removed installer test
  class; package string updated.
- `VIBEIDE_MODULE_MIGRATION.md`, `VIBEIDE_REMOVALS.md` — migration
  documentation describing the old package/removed tooling (intentionally
  historical).

## CI changes

- `.github/workflows/android-build.yml` now triggers on push to `main` and
  `dev`; generates signing keystore at the path the SigningConfigPlugin
  expects; APK artifacts uploaded (release + debug).
- `.github/workflows/build.yml`: removed the `run_tooling_api_tests` job (the
  `:tooling` module was removed in checkpoint-02); `publish`,
  `publish_snapshots`, and `run_connected_checks` are now
  `workflow_dispatch`-only so a plain `main` push runs `build_apk` +
  `run_unit_tests`.

## Verification performed (static)

- 0 files under app source trees still contain `com.hmx.ide`.
- 0 `package com.hmx.ide` declarations anywhere in app source.
- 0 package-vs-directory mismatches.
- All EventBus index imports match generated package.
- Manifest authorities/namespaces consistent with `com.hmx.webide`.
- Build files reference only build-logic packages/plugin IDs.