# VibeIDE V1 Web Stack — Locked Scope

This document is the **authoritative source of truth** for which web technologies VibeIDE V1
supports, which are explicitly out of scope, and the policies that enforce that boundary. It was
produced by auditing the codebase and removing all confirmed out-of-scope web support.

> Scope is enforced in code (not just docs):
> - `core/.../ai/tools/FileWriteGate.kt` — only blocks out-of-scope framework configs, secrets
>   (`.env*`), alternative package-manager lockfiles, and `.git` metadata. `package.json`,
>   `package-lock.json`, `vite.config.*`, `tsconfig*.json`, `tailwind.config.*`, `postcss.config.*`,
>   `.npmrc`, `.editorconfig`, `.gitignore` are **normal project files the AI may create/edit**.
> - `core/.../ai/terminal/PackageManagerDetector.kt` — npm only.
> - `editor/.../editor/language/WebLanguageProvider.kt` — no `.vue` mapping (removed).
> - `core/.../ai/context/ProjectScanner.kt` — no `vue` extension (removed).

---

## 1. Supported V1 Web Stack

### Languages / file types
- **HTML** (`.html`, `.htm`)
- **CSS** (`.css`)
- **JavaScript** (`.js`, `.mjs`, `.cjs`)
- **TypeScript** (`.ts`, `.tsx`)
- **JSX** (`.jsx`)
- **SVG** (`.svg`)
- **JSON** (`.json`) — including `package.json`

### Runtimes / package managers
- **Node.js** — the only supported JavaScript runtime.
- **npm** — the only supported package manager.

### Frameworks / libraries
- **React** + **React DOM**
- **Vite** (dev server + build)
- **Tailwind CSS**
- **React Router** (supported as a normal npm dependency in `package.json`; no special VibeIDE runtime)
- **shadcn/ui** — see §7 (approved ecosystem, project-owned, not bundled)

### Tooling that remains
- WebView-based **Preview** (static + dev server).
- Project templates that generate plain HTML/CSS/JS (`WebProjectTemplates`).
- AI tool system (registry + write gate + file ops).

---

## 2. Out of Scope (explicitly removed / not supported in V1)

The following were confirmed NOT in the V1 list and are **removed or blocked**:

### Frameworks
- Vue, Svelte, Angular, Next.js, Nuxt, Astro, Solid, Qwik, Ember, Gatsby, Remix, and any other
  UI / meta framework.

### Package managers (removed references)
- **pnpm**, **yarn**, **bun** — `PackageManagerDetector` and `PackageCache` were trimmed to npm only.

### Linters / formatters (out of scope as first-class V1 technologies)
- **ESLint**, **Prettier**, **PostCSS**, **Autoprefixer** when used as separate, user-facing tools.
  They may still exist **internally** as transitive dependencies of Vite/Tailwind (that is fine and is
  not removed). Their standalone config files (`.eslintrc`, `.prettierrc`) are blocked from AI writes.

### Removed code references
- `WebLanguageProvider` `.vue -> html` mapping — **removed**.
- `ProjectScanner` `vue` extension — **removed**.
- `PackageManagerDetector` PNPM / YARN / BUN entries — **removed**.
- `PackageCache` pnpm / yarn / bun cache env + sub-directories — **removed**.

### Write-gate blocks for out-of-scope tech
The AI cannot write the following (they signal out-of-scope frameworks/build tools):
`next.config.*`, `angular.json`, `webpack.config.*`, `rollup.config.*`, `babel.config.*`,
`vercel.json`, `netlify.toml`, `now.json`, `firebase.json`, `.eslintrc`, `.prettierrc`,
`yarn.lock`, `pnpm-lock.yaml`, `bun.lockb`, `npm-shrinkwrap.json`, `.env*`, and any `.git` metadata.

---

## 3. Package Manager Policy

- **npm is the only supported package manager.** It is the entry point for all dependency installs,
  scripts, and dev servers.
- `pnpm`, `yarn`, `bun` are **not supported** in V1. Their lockfiles are blocked from AI writes, and
  detection code no longer lists them.
- All AI-generated web projects use `package.json` + `package-lock.json` + `npm` scripts
  (`npm install`, `npm run dev`, `npm run build`).

---

## 4. Runtime Policy

- **Node.js** is the only runtime used to execute web projects (dev server, build).
- No other runtime (Deno, Bun, etc.) is bundled or supported in V1.
- The runtime is expected to be available in the execution environment; VibeIDE does not ship a
  Node.js binary inside the app for V1 (download/install is out of scope for this task).
- Preview uses the WebView for static output and delegates dev-server output to whatever Node.js
  provides.

---

## 5. Dependency Policy

- Dependencies are installed via **npm** into the project's own `node_modules`.
- VibeIDE does **not** bundle framework code (React, Vite, Tailwind, etc.) inside the APK. The AI
  generates projects that declare these as normal npm dependencies; the user installs them with npm.
- Internal AndroidIDE/VibeIDE libraries (editor, LSP, tree-sitter, WebView, AI, tool system, preview)
  are **not** affected by this scope lock and remain intact.

---

## 6. Global Package Cache Policy (documented, not implemented in V1)

Goals (for when Phase 3 terminal/package execution lands):

- A **global, VibeIDE-managed cache** lives in **app-private storage**
  (`Context.getFilesDir()` / `getExternalFilesDir()`), **never inside a user project**.
- Each project keeps its own `package.json` + `node_modules`; the cache only stores downloaded
  tarballs so installs are reused across projects.
- The cache is pointed at via the `npm_config_cache` env var (implemented in `PackageCache.cacheEnv()`
  — currently npm-only).
- Per-project `node_modules` is always project-specific; only the download cache is shared.

This policy is recorded now so the Phase 3 implementation matches V1 scope without re-auditing.

---

## 7. React / Vite / Tailwind Architecture (how V1 supports them)

- **React / React DOM** — supported as npm dependencies; JSX/TSX files are first-class source
  (`WEB_EXTENSIONS` + `WebLanguageProvider` map `jsx`->javascript, `tsx`->typescript).
- **Vite** — project detection (`WebProjectDetector` VITE markers) and preview dev-server wiring
  remain. `vite.config.*` is an editable project file (not blocked by the gate).
- **Tailwind CSS** — supported as an npm dependency; `tailwind.config.*` and `postcss.config.*` are
  editable project files (not blocked).
- **React Router** — supported purely as a project-level npm dependency; no dedicated VibeIDE runtime.
- **shadcn/ui** — approved V1 ecosystem. It is **not bundled** in the APK; the AI generates shadcn
  projects by adding its dependencies/components through normal npm flow. No special VibeIDE code is
  required or removed for it.

---

## 8. How to Add an Out-of-Scope Technology Later

To promote a currently-out-of-scope technology into V1:

1. Add it to §1 (Supported) and remove it from §2 (Out of Scope).
2. Re-enable its write-gate entry in `FileWriteGate.PROTECTED` (allow the config file).
3. If a package manager is involved, add it to `PackageManagerDetector` + `PackageCache` and update §3.
4. Add detection/Preview support in `WebProjectDetector` / `ProjectPreview` as needed.
5. Keep this document as the single source of truth — update it in the same change.

---

_Locked: VibeIDE V1. Anything not listed in §1 is unsupported until promoted via §8._
