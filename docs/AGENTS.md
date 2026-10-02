# Agent guide

Read this first. It is tool-neutral and is reached from the README. To have it loaded automatically on
your own machine, keep an untracked `CLAUDE.local.md` (git-ignored) that contains `@docs/AGENTS.md`. It tells you where things are and the rules for changing them. The detailed
knowledge lives under [`docs/`](README.md), organised by topic; open only what the task needs.

## What this is

Impulse Launcher is a 3D home screen for the Haval H6 head unit (Snapdragon SA8155 / Adreno 640, a
WebView 91 on Android 9, a 1920x720 panel). A single `index.html` (React and three.js, no bundler)
runs inside a thin Android shell (`app/`), and the vehicle bus reaches it through the
[Impulse](https://github.com/bobaoapae/haval-app-tool-multimidia) app. The code is AGPL-3.0; the 3D
models and images are a separate, private bundle fetched at build time (`assets.lock.json`), so a
fresh checkout builds and tests but starts without the car.

## Where to look

| If you are working on... | Read |
|---|---|
| Anything touching speed, FPS, load time or a vehicle signal | [measuring-and-performance](engineering/measuring-and-performance.md) |
| The boot intro, the render loop, x-ray, the transparent canvas | [rendering-and-boot](engineering/rendering-and-boot.md) |
| Wheels, rims, the blur sprite | [wheels](engineering/wheels.md) |
| A signal, Impulse, fuel, range, navigation data | [vehicle-data-and-impulse](engineering/vehicle-data-and-impulse.md) and [docs/vehicle-data](vehicle-data/signals-and-limits.md) |
| A card, a widget or a popup (the vocabulary) | [ui-surfaces](architecture/ui-surfaces.md) |
| One feature | [docs/features](README.md#features): media, driving, clock, energy, power flow |
| The Android shell and its bridges | [android-shell](architecture/android-shell.md) |
| Assets, GLB editing, reaching the car or the emulator | [assets-build-and-devices](engineering/assets-build-and-devices.md) |
| Running or writing tests | [testing](engineering/testing.md) |
| Cutting a release | [RELEASING](release/RELEASING.md) |
| Licenses, credits, third-party material | [THIRD_PARTY](THIRD_PARTY.md), [LICENSE](../LICENSE), [LICENSE-ASSETS](../LICENSE-ASSETS) |

The full map is [docs/README.md](README.md).

## Guidelines

1. **Document only what matters.** Add new features, decisions, measured facts and contracts, and
   nothing else: no task logs, no review rounds, no hand-off prompts, no "what I did today". Update
   the document that already owns the topic instead of starting a new one, and let each fact live in
   exactly one place. Markdown lives under `docs/`; `README.md` is the only Markdown file in the
   repository root. A new document must be added to the index in `docs/README.md`.
2. **Keep AI-agent memory out of the repository.** Do not commit agent folders or files
   (`.claude/`, `.cursor/`, `.codex/`, memory directories, `CLAUDE.local.md`, per-tool rule files).
   Your own memory stays local, and so do per-tool entry files (no `CLAUDE.md` or similar in the root).
3. **No personal data.** No names, e-mail addresses, account or user names, local paths, Wi-Fi names,
   locations, private network addresses, tokens or keys, in code, comments, docs, commits or test
   fixtures. Keep them in your own local memory. If a fact is useful but personal, write the generic
   version (`<car-ip>`, "a suburb-level name").
4. **Follow the existing structure and style.** Use the surrounding code and the neighbouring
   documents as the reference: file layout, naming, comment density, heading and table style. If you
   think a convention should change (a style, a name, a folder), ask the maintainer first.
5. **OEM material stays generic.** [docs/vehicle-data/oem-reference](vehicle-data/oem-reference/README.md)
   is the only place for what was learned about the vehicle maker's software, and it records
   interoperability facts (names, keys, behaviour). Never commit OEM APKs, extracted files, device
   identifiers or dumps; keep the full detail in your own local memory.
6. **Do not duplicate code, or text.** Before writing a helper, a handler or a template, find the
   existing one and extend it (the `_live*` seam for hot signals, one shared template for a popup and
   its widget, one SVG source for web and native). Two copies of a rule drift apart.
7. **The car's resources are scarce; protect them.** The main thread of the head unit is the
   bottleneck, not the GPU. For every change think about CPU, memory, start-up time and the on-demand
   render loop; never route a bus signal through `setState`; avoid per-frame work, allocations and
   large assets. Measure before and after on the car (`npm run car:perf`) or say plainly that you
   could not, and report any effect on frame rate, load time or UX. A change that costs performance
   is not finished until it is measured and the cost is accepted. Compare arms interleaved, never
   one after the other. [measuring-and-performance](engineering/measuring-and-performance.md)
   explains why.
8. **Validate every change and keep the tests current.** Run `node scripts/run-tests.mjs` (and
   `./gradlew testDebugUnitTest` when the native side changed) before you finish. A change in
   behaviour ships with a test, or with the reason none is possible; negative-control new assertions;
   never edit a test until it passes. Say which tests you ran and how. The base, the lists that
   shape the run and the CI jobs are described in [testing](engineering/testing.md).

## Before you finish

- The tests you ran, and what they do not cover (emulator versus car).
- Any performance effect, measured or not.
- Docs updated where a feature, a decision or a contract changed, and the index if you added one.
- Nothing personal, no agent files, no secrets, no extracted OEM material in the diff.

## Working agreement

Confirm with the maintainer before anything destructive or outward-facing: force-pushes, deleting
branches or releases, publishing, changing a signing key. The emulator and the car belong to the
maintainer: do not reset, reinstall over or reboot them without asking. Releases follow
[RELEASING](release/RELEASING.md): stable tags come from `main`, preview tags from `preview`.
