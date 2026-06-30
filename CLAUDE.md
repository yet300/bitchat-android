# bitMessage (bitchat rewrite) — project rules

## Process
- One iteration = branch `bitMessage/<slug>` → green gate (`:core:*` + `:feature:*` tests, `:shared:assemble`, `:androidApp:testDebugUnitTest`, `:androidApp:assembleDebug`) → `merge --no-ff` into `bitMessage/main`. (The legacy `:app` module was removed in the A–D rewrite; the application module is now `:androidApp`.)
- Commits and code comments in English. Responses to the user in Russian.
- `docs/MIGRATION_PLAN.md` and `docs/FEATURE_MAP.md` are deliberately untracked — never `git add` them.

## Architecture
- Target stack: Decompose + MVIKotlin + Metro DI, Compose Multiplatform (Material3).
- `:shared` module hosts the CMP UI, integrates all feature/core modules, and owns the DI setup (architecture modeled on the BlockBlast project).
- Single `@DependencyGraph` per platform; Metro annotations in lower modules are metadata only.
- Dependency Rule: `:core:domain` (pure KMP) ← `:core:data` ← `:core:transport` / `:core:crypto` / `:core:common`.
- Navigation: Decompose; prefer `ChildPanels` for list/detail (chats/details) over Activity-based flows.

## Code rules
- No Android `Context` and no business logic inside `@Composable` functions.
- Max 800 lines per file. If a file outgrows that, refactor meaningfully (SOLID, DRY, KISS) — no mechanical splits.
- Refer to classes by simple names with imports; never fully-qualified names inline in code.
- Write code comments only for constraints the code can't express.
- Coroutine dispatchers: never use `Dispatchers.IO` directly (absent on native — breaks commonMain/iOS) and avoid hardcoding `Dispatchers.Default/Main`. Inject `com.app.common.AppDispatchers` (ctor param, defaults to `AppDispatchers()`) and use `dispatchers.io` / `.default` / `.main` / `.unconfined`. commonMain code with no DI seam may reference the `ioDispatcher` expect val. Migrate existing direct `Dispatchers.*` call sites to `AppDispatchers` opportunistically when touching a file.

## Hard invariants (do not break)
- iOS wire compatibility: `BinaryProtocol` BLE bytes, Nostr NIP-01/17 JSON, Noise XX. `BinaryProtocolTest` + `BinaryProtocolGoldenTest` must stay green.
- Foreground service owns the mesh lifecycle.
- Never touch `core/crypto/**/southernstorm/`.
- Do not upgrade dependencies without an explicit request.

## Skills
Project skills live in `.agents/skills/` and are exposed to Claude Code via symlinks in `.claude/skills/` (auto-discovered). Available: decompose-component, decompose-navigation, decompose-compose, mvikotlin-code, metro-di, compose-multiplatform-adaptive-design, mobile-android-design, edge-to-edge (system/), r8-analyzer (performance/). Use them whenever the task touches their domain.
