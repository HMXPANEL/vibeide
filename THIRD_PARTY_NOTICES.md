# THIRD_PARTY_NOTICES.md

Legal attribution for VibeIDE.

VibeIDE is a fork of **AndroidIDE** (GPL-3.0). Reusing GPL code does **not**
remove the obligation to keep the GPL license and its notices. This document
records every reused component, its license, and the required notices.

**VibeIDE itself is licensed under the GNU General Public License v3.0**
(see `LICENSE` in the repo root, retained from AndroidIDE). This is **not**
optional attribution; it is a legal requirement. Any distribution of VibeIDE
must keep the LICENSE file and these notices.

---

## 1. Upstream project

| Component | AndroidIDE |
|---|---|
| Original project | [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE) |
| License | GPL-3.0-or-later |
| Source URL | https://github.com/AndroidIDEOfficial/AndroidIDE |
| How VibeIDE uses it | The majority of the codebase is a fork of AndroidIDE (this repository started from `AndroidIDEOfficial/AndroidIDE`). All source files retain their AndroidIDE copyright headers. |
| Required notices | Keep the GPL-3.0 license text (`LICENSE`) and the per-file headers (`This file is part of AndroidIDE ...`). Do not remove, even when rebranding the app. |

---

## 2. Direct submodule

| Component | logback-android |
|---|---|
| Original project | AndroidIDE logback-android |
| License | LGPL-2.1-or-later / LGPL-2.1 (logback is LGPL; the Android-ported core is built from this source) |
| Source URL | https://github.com/AndroidIDEOfficial/logback-android |
| How VibeIDE uses it | Git submodule at `composite-builds/external/logback-android`; patched `logback-core` for Android (avoids `Class.getModule()` on ART). Used by `logging/logger`. |
| Required notices | Preserve the LGPL notice shipped with the submodule. LGPL requires offering source / relink instructions for linked libraries — retain the submodule and its license files. |

---

## 3. Retained in-repo vendored libraries

| Component | sora-editor (tree-sitter integration) |
|---|---|
| Original project | Rosemoe sora-editor |
| License | MIT |
| Source URL | https://github.com/Rosemoe/sora-editor |
| How VibeIDE uses it | `editor/treesitter` is an adapted/ported subset (`io.github.rosemoe.sora.editor.ts.*`). |
| Required notices | Keep the upstream copyright headers in `editor/treesitter` source files and dependency notices. |

| Component | Flashbar |
|---|---|
| Original project | ishanvyas/Flashbar (adapted) |
| License | Apache-2.0 |
| Source URL | https://github.com/ishanvyas/Flashbar |
| How VibeIDE uses it | `utilities/flashbar` (vendored toasts). |
| Required notices | Retain Apache-2.0 headers in vendored sources. |

| Component | AndroidTreeView |
|---|---|
| Original project | bmelnychuk/AndroidTreeView (adapted) |
| License | Apache-2.0 |
| Source URL | https://github.com/bmelnychuk/AndroidTreeView |
| How VibeIDE uses it | `utilities/treeview` (file explorer tree). |
| Required notices | Retain Apache-2.0 headers in vendored sources. |

| Component | GreenRobot EventBus |
|---|---|
| Original project | greenrobot/EventBus (forked) |
| License | Apache-2.0 |
| Source URL | https://github.com/greenrobot/EventBus |
| How VibeIDE uses it | `event/eventbus` + `event/eventbus-android`. |
| Required notices | Retain Apache-2.0 headers in forked sources. |

| Component | Eclipse LemMinX XML DOM |
|---|---|
| Original project | eclipse-lemminx (re-hosted subset) |
| License | EPL-2.0 (re-hosted subset in `xml/dom` — **scheduled for removal**) |
| Source URL | https://github.com/eclipse-lemminx/lemminx |
| How VibeIDE uses it | XML DOM parsing for the Android XML LSP (being removed). |
| Required notices | Keep headers while files remain in the tree. |

| Component | OpenJDK javac / JDT / NetBeans compiler bits |
|---|---|
| Original project | OpenJDK, Eclipse JDT (in composite builds + `java/javac-services`) |
| License | GPL-2.0-with-classpath-exception / EPL-2.0 (respectively) |
| Source URL | https://openjdk.org, https://eclipse.dev/jdt |
| How VibeIDE uses it | On-device Java compiler for the Java LSP (being removed). |
| Required notices | Keep composite-build license files while they remain. |

---

## 4. Dependencies pulled from Maven (build/runtime)

| Component | License | Used for |
|---|---|---|
| Rosemoe sora-editor | MIT | code editor |
| AndroidIDE tree-sitter bindings (`com.itsaky.androidide.treesitter`) | MIT / (grammars carry their own licenses) | native syntax trees |
| AndroidX libraries | Apache-2.0 | UI/OS |
| Material Components | Apache-2.0 | UI |
| Kotlin / coroutines | Apache-2.0 | language/runtime |
| JGit | EDL-1.0 (BSD-style) | git |
| Retrofit, OkHttp transitives | Apache-2.0 | HTTP/AI APIs |
| Gson | Apache-2.0 | JSON |
| Guava | Apache-2.0 | collections |
| logback | LGPL-2.1 / EPL-1.0 | logging |
| slf4j | MIT | logging |
| appintro | Apache-2.0 | onboarding |
| utilcodex | Apache-2.0 | Android utils |
| markwon | Apache-2.0 | markdown |
| Tree-sitter grammars (java/json/kotlin/log/xml) | MIT (individual grammar projects) | highlighting |
| AndroidChart (AppDevNext) | Apache-2.0 | memory chart |
| AutoValue / AutoService | Apache-2.0 | codegen |
| jsoup | MIT | HTML parsing |
| Realm | Apache-2.0 | AI memory persistence |
| security-crypto | Apache-2.0 | token encryption |
| hiddenapibypass | Apache-2.0 | system API access |
| leakcanary | Apache-2.0 | debug leak detection |

**Action**: the app's existing "About → licenses" screen already aggregates
third-party notices (AndroidX/appcompat generated notices); keep that and extend
it with this document.

---

## 5. What this means during migration

1. **Do NOT delete** `LICENSE`, `.gitmodules`, or the `logback-android`
   submodule's license files.
2. **Do NOT strip** the `This file is part of AndroidIDE` GPL headers from
   retained files.
3. Vendored modules (`flashbar`, `treeview`, `eventbus`, `editor/treesitter`,
   composite `logback-core`) keep their license headers even when re-badged.
4. New VibeIDE-only files should carry a header like:
   `This file is part of VibeIDE.` (VibeIDE remains GPL-3.0 as a derivative work.)
5. Files **removed** during migration (`xml/*`, `java/*`, `tooling/*`, etc.) are
   removed entirely — no license obligations for code no longer distributed.

This document is not legal advice; when in doubt, retain the upstream
license/copyright as-is.