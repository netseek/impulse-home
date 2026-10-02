# Documentation

How the documentation is organised. Agents: start from [AGENTS.md](../AGENTS.md), which holds the rules
for working in this repository. Every document under `docs/` is listed here, and a test
(`scripts/test-docs.mjs`) fails if one is missing or a link is broken.

## Engineering notes

Hard-won facts about this hardware. Everything here was measured on the car, not assumed.

| Document | What it covers |
|---|---|
| [measuring-and-performance](engineering/measuring-and-performance.md) | The one rule (measure on the car), how to read the diagnostics, why a hot signal must never reach React, cold start, things that look like wins and are not |
| [rendering-and-boot](engineering/rendering-and-boot.md) | The boot intro and hand-off, the transparent canvas and additive blending, post-boot swaps, x-ray as a second model, the tone grade |
| [wheels](engineering/wheels.md) | Spin as a sampling problem, rim fitting and balance, the blur sprite and its two layers, measuring without fooling yourself |
| [vehicle-data-and-impulse](engineering/vehicle-data-and-impulse.md) | Refuel detection, the fuel-litres derivation, hazard and mirror findings, the navigation card's geocoder, the range forecast ledger |
| [assets-build-and-devices](engineering/assets-build-and-devices.md) | The asset bundle, retiring and editing models, standalone bodies, reaching the car and the emulator |
| [testing](engineering/testing.md) | The test base, the lists that shape a run, CI, and how to keep it current |
| [cluster-ab-plan](engineering/cluster-ab-plan.md) | A measured A/B procedure for the cluster-lag investigation |

## Architecture

| Document | What it covers |
|---|---|
| [ui-surfaces](architecture/ui-surfaces.md) | The vocabulary: card (native rail), widget (the board), popup (the focused workspace) and how they connect |
| [android-shell](architecture/android-shell.md) | The Android host: WebView, bridges, broadcasts, the settings it persists |
| [modularization](architecture/modularization.md) | The plan for breaking `index.html` into modules without a runtime cost |

## Features

| Document | What it covers |
|---|---|
| [media-card](features/media-card.md) | Now playing, transport and the app behind it |
| [driving-card](features/driving-card.md) | Drive mode, power mode and energy recovery |
| [clock-card](features/clock-card.md) | The four faces, their settings, and how native and web share one drawing |
| [energy-workspace](features/energy-workspace.md) | Instant, trip and history data: the recorder, the maps, the insights |
| [power-flow](features/power-flow.md) | The chassis overlay: data contract, SOC modules, flow and motion |

## Vehicle data

| Document | What it covers |
|---|---|
| [signals-and-limits](vehicle-data/signals-and-limits.md) | What each widget reads, what is simulated, and what it must say about it |
| [oem-reference](vehicle-data/oem-reference/README.md) | A generic reference for the maker's software: packages, properties, evidence levels, open questions |
| [energy-assistant](vehicle-data/oem-reference/energy-assistant.md) | The maker's energy application: screens, keys, and what this app does beyond it |

## Release

| Document | What it covers |
|---|---|
| [RELEASING](release/RELEASING.md) | The signing key, the secrets, the two channels, cutting a release |

## Adding or changing a document

Follow [AGENTS.md](../AGENTS.md): add only what is relevant, put each fact in one place, keep personal
data and agent memory out, and add the file to the table above. Screenshots in `docs/images/` come
from an emulator with simulated data.
