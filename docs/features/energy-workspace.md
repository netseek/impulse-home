# ENERGY workspace

Status: **implemented.** One ENERGY workspace replaces the old CONSUMPTION card, widget and
popup and covers instant, trip and long-term data. It was designed from source reading and
then built in the order listed under *Implementation notes*; the sections below describe how it
behaves and why. Items still unverified on the car are marked as such, and every number that
drives a decision still has to be measured there (see
[measuring-and-performance](../engineering/measuring-and-performance.md)).

How the OEM energy app works, and which of its signals this workspace does and does not use,
is in [vehicle-data/oem-reference](../vehicle-data/oem-reference/README.md).

## Decisions taken

| # | Decision |
|---|---|
| 1 | **Online map is primary, blank route shape is the fallback.** When a trip closes, render a low-res static snapshot (tiles + route) and store it, so the trip list and offline viewing never need the network again. |
| 2 | **Detailed data (GPS track, per-trip time series) for the last 30 trips.** Trip summaries, snapshots and daily/monthly aggregates are kept forever. |
| 3 | **Impulse `TripConsistencyManager` is not used.** The viewer records its own trips. |
| 4 | **One ENERGY workspace and one rail card.** No separate Trips card. |
| 5 | **Refuel log + fuel price lookup is a future, optional last phase** (see the end of this file). |

## Benchmark (2025-2026)

- **Tesla** — trip meters (Current Drive, Since Last Charge, unlimited named
  custom trips with pause/reset), per-trip energy graph, energy graph widget next
  to the media player, Drive / Park tabs, range projected from recent driving.
- **Mercedes EQ** — From Start (auto-resets after 4 h off), From Reset, since
  new; consumption histogram beside the energy flow.
- **BMW** — since trip / individual / since factory; phone app trip list with
  consumption and average speed.
- **Porsche** — trip list (duration, avg speed, consumption), compare with last
  month, trips < 2 h apart merged.

Common shape: **NOW / TRIP / HISTORY**. None of them uses navigation data for
insight — that is where this can go further.

## Data sources

"Theme-validated" means Impulse's cluster theme already consumes the key and
the format below is taken from its parser, not guessed.

| Value | Key / source | Format | Status |
|---|---|---|---|
| Odometer | `car.basic.total_odometer` | plain km, passed raw to the theme (`InstrumentProjector2`: `control('odometer', value)`) | theme-validated; in Impulse `DEFAULT_KEYS`, so already broadcast. **0.1 km (100 m) resolution**, confirmed on the car. |
| Instant fuel | `car.basic.instant_fuel_consumption` | `{metric,value}`: metric 1 = running L/100 km (theme shows 100/value km/L), metric 4 = idle L/h | theme-validated; viewer `_parseFuelInst` mirrors it |
| Instant energy | `car.ev_info.Instant_energy_consumption` | raw | theme-validated, but the viewer derives kWh/100 from pack kW x speed like the cluster does |
| Pack power | `power_battery_voltage` x `cur_charge_current` | V, A | already used for `evPowerKw` |
| Trip-computer averages | `avg_fuel_consumption`, `accumulated_odometer`, `accumulated_drivetime`, `vehicle_speed_since_reset` | strings to the theme | theme-validated (since-reset set). Reset semantics unknown. |
| Journey averages | `cur_journey_avg_fuel_consume`, `avg_energy_consume_info_since_startup` | as above | already subscribed |
| Speed, gear, READY | `vehicle_speed`, `gear_status`, `driving_ready_state` | | already subscribed |
| Tank level | `remain_fuel_percentage` x 55 L | % | theme-validated; share the 55 L constant with Impulse |
| EV-only share | `haval.power.flow` (ICE on/off) | packed | already subscribed |
| Position, altitude | `LocationManager` GPS (`PlaceGlance`) | | fix is healthy (`hAcc=1`, 7 sats) |
| Place names | Nominatim over HTTPS (`PlaceGlance`) | | working; platform geocoder is dead on this MMI |
| Destination | `app.navigation.directions` (AA, only while guiding) | JSON | already subscribed |

Keys the OEM app reads but Impulse does not monitor (`energy_recovery_info`,
`cur_journey_odometer`, `cur_journey_drivetime`, `kilometer_avg_*`,
`motor_power`) do not reach us. Either add them to Impulse's monitored
properties (Current Values screen, `CAR_MONITOR_PROPERTIES` — no code change) or
do without: regen kWh is integrated from pack power anyway.

### Measured, not averaged

Per trip, integrated by the recorder rather than read from the trip computer:

- **Distance** — odometer delta (0.1 km) is authoritative; speed integration
  interpolates the live value between ticks.
- **Electricity out / recovered (kWh)** — integrate V x I, split by sign.
- **Fuel (L)** — integrate instant fuel: running L/100 km x distance step, idle L/h x time step.
- **EV share** — distance and time with the ICE off.
- **Idle time, stops, harsh accel/brake** — speed derivative.
- **Route, climb, start/end place, destination** — GPS + geocoder + AA.

### Open checks (cannot be read from theme code)

1. ~~`total_odometer` resolution~~ — 0.1 km. The odometer delta is the trip
   distance of record; speed integration only fills the live value between
   100 m ticks.
2. When the journey / since-startup keys reset.
3. Signal update rates while driving (affects integration step).
4. `remain_fuel_percentage` step size (matters for refuel detection only).

Build these into the recorder rather than a separate capture phase: store raw
start/end values of each key per trip plus odometer-vs-integrated distance, so the
first real drives validate the integration.

## Architecture

### Native `TripRecorder` (Java)

The WebView cannot record: once another app takes focus, rAF stops and timers
drop to 1 Hz (`CLAUDE.md`, hidden WebView). The recorder is **process-scoped**
(`TripRecorder.get`) and registers its own receiver for
`ACTION_VEHICLE_EVENT_CHANGED` on the application context, delivered on its
worker thread, plus a `LocationManager` GPS listener.

It was first tied to `MainActivity` and fed by the activity's receiver. Measured
on the emulator 2026-09-13: the activity was finished and recreated in the same
process 6 s into a simulated drive, the new recorder restored the checkpoint with
READY unknown, and integrated 5.8 s of a 61 s trip. `MainActivity.onDestroy` now
only flushes the recorder.

- **Trip boundaries.** Open on READY on. Close after READY has been off for a
  merge gap (start at 10 min, tunable) so a fuel stop stays one trip.
- **Power-off.** The MMI can shut down the moment the car is switched off, so
  checkpoint the open trip to disk continuously. On boot, close any trip whose
  last sample is older than the merge gap.
- **Sampling.** Accumulate integrals on every signal (cheap); store a point
  every ~1 s or ~20 m, whichever first.
- **Storage: SQLite.**
  - `trip_points` (trip_id, t, lat, lon, alt, speed, kw, fuel_rate, ice_on) — **last 30 trips only**
  - `trips` (summary, place names, destination, raw start/end key values, snapshot path) — forever
  - `days` (date, km, fuel L, kWh out, kWh regen, EV km, drive time) — forever
  - months — a `GROUP BY` over `days`, not a table
  - `open_trip` — the single-row checkpoint
- **Clock.** Everything is stamped with the wall clock, and the MMI's clock may
  be wrong at boot until GNSS/NTP corrects it. A step longer than 10 s is never
  integrated across, so a correction cannot inflate a trip, but the START time of
  a trip opened before the correction can be off. Check on the car.
- **Snapshot at close.** Fetch the tiles that cover the route, draw the polyline
  onto a `Bitmap`, save a ~25 KB JPEG. With no network, queue it and retry when
  online; the list shows the blank route shape until then.
- **Bridge.** `getTrips(offset, limit)`, `getTrip(id)`,
  `getDays(from, to)`, `getMonths()`, `getLiveTrip()`. Live values reach the page
  on a low-rate timer through the `_live` seam, **never** `setState` per signal.

### Map

- Popup trip detail: interactive map from bundled tiles code (Leaflet vendored,
  CDN is not reachable offline) over OSM tiles. Shown only in the popup, never on
  a widget while driving.
- OSM tile policy: identifying `User-Agent`, no bulk prefetch, honour caching. A
  few tiles per closed trip is well within it. Revisit the provider only if usage grows.
- Fallback everywhere: the route polyline on a plain background.

## Surfaces

Keep the stored id `consumption` (rail catalog, `BOTTOM_CARD_ACTIONS`,
`openConsumption`, saved layouts) and change only the label to ENERGY — same
precedent as `appCar`. Renaming the id means a migration across native, web and
saved desktops for no user-visible gain.

| Surface | Content |
|---|---|
| Rail card (native `QuickCardGraphicView`) | Current trip average + distance, mini sparkline. Idle: last trip. |
| Widget 1x1 | NOW: instant consumption, one bar |
| Widget 2x1 | Current trip: avg, km, time, EV % |
| Widget 2x2 | Trip + consumption-over-distance graph |
| Widget 3x1 / 3x2 | History: 7 d / 30 d bars, vs last month |
| Popup | Tabs **NOW · TRIPS · HISTORY**; trip detail opens inside the same frame |

**NOW** — hero instant km/L or kWh/100, flow line ("EV · regen 12 kW"), current
trip distance / time / EV share.

**TRIPS** — current trip live; list of trips ("Home → Centro · 18 km · 16.2 km/L ·
64% EV", snapshot thumbnail); detail with map, consumption and altitude against
distance, stops, and 2-3 observations derived from *that* trip.

**HISTORY** — 7 d / 30 d / 12 m bars, compare with last month, lifetime totals,
best / worst trips.

Rules: one hero number per view, at most three secondary; one unit per quantity
everywhere; every value carries its provenance (vehicle / derived / recorded); no
static tips.

**Units:** fuel defaults to **km/L**, selectable L/100 km;
electricity is selectable too (kWh/100 km or km/kWh). One preference per
quantity, applied to every surface — rail, widget and popup never disagree.

## Implementation notes

0. **Consumption perf fix** — *done (437baf4), desktop-verified, not yet
   measured on the car.* The per-signal `setState` is gone and the derived
   `evTrip` reads ESTIMATE. Measure before/after with `npm run car:perf`.
1. **`TripRecorder`** — *implemented, JVM-tested, not yet run on the car.*
   `TripEngine` (pure Java: boundaries, integrals, harsh events, climb,
   checkpoint) is covered by `app/src/test/.../TripEngineTest.java` and
   `TripSignalsTest.java` (`gradlew :app:testDebugUnitTest`). `TripStore`
   (SQLite + retention), `TripRecorder` (worker thread, GPS, 15 s checkpoint) and
   `window.TripBridge` (`getLiveTrip`, `getTrips`, `getTrip`, `getDays`,
   `getMonths`) are wired into `MainActivity`. Logcat tag `H6Trip` prints each
   closed trip with the raw trip-computer values for comparison.
2. **Web data layer** — *implemented, desktop + emulator checked, not on the
   car.* `_energyModel` feeds rail (`_energyRailCard`), widget
   (`_consumptionWidgetView`) and popup (`_energyPopupView`, re-exported with
   the `focused` prefix). Live readouts are painted by `_energyTick` at 1 Hz
   into `[data-en]` nodes; React commits only on taps and on a trip closing.
   Units: `h6_energy_fuel_unit` (`kml` default / `l100`), `h6_energy_ev_unit`
   (`kwh100` / `kmkwh`). Desktop without `TripBridge` shows labelled DEMO data.
3. **UI** — *implemented.* Widget sizes 1x1 / 1x2 / 2x1 / 2x2 / 3x1 / 3x2;
   popup NOW · TRIPS (list + detail with route shape and speed profile) ·
   HISTORY (7 days / 30 days / 12 months). Native rail `case "consumption"`
   draws seven-day bars + EV share (`drawEnergy`, `energyBars` payload field);
   the card's text column carries the numbers. Contract:
   `scripts/test-consumption-card-contract.mjs` (9 checks, negative-controlled).
4. **Map** — *snapshot + place names implemented; emulator-checked only.*
   `TripMapWorker` (own thread; tiles block for seconds) sweeps the database for
   trips whose `snapshot_path` or `start_place` is still NULL, so a trip closed
   offline is caught up later. `TripMapRenderer` fits the route into a 640x400
   OSM tile mosaic (`TripMapMath`, JVM-tested), draws route + start/end markers
   + the required "© OpenStreetMap contributors", and saves
   `files/trip-maps/<startMs>.jpg`; tiles are cached 30 days under
   `cache/osm-tiles`. `ReverseGeocoder` (extracted from `PlaceGlance`, now
   rate-limited to 1 req/s for the whole process) names both ends. The page
   shows the snapshot via `TripBridge.getTripMap` as an `<img>`, the outline as
   fallback, and "Riverside → Downtown" titles. Schema v2 adds `map_attempts`
   (`ALTER TABLE`, never a drop). Empty string = "tried, nothing to draw".
   **Interactive map** — *implemented, desktop-checked.* EXPLORE MAP in a closed
   trip's detail opens a pan / zoom view on live OSM tiles
   (`_energyMapCreate`): drag to pan, + / − / fit buttons (44 px, touch), wheel
   on desktop, route + start/end markers + OSM attribution. Driven imperatively
   into an empty React div, so a drag never commits. Offline the tiles fail and
   the route stays on a plain background. Projection shared with TripMapMath;
   `scripts/test-energy-map.mjs` checks it against the OSM wiki formula.
   **Android Auto guidance** — *implemented, JVM-tested.* `app.navigation.directions`
   carries no destination name (only turns, `remaining_m`, `remaining_s`), so a
   trip records the plan as first stated (planned km / time), what was driven
   while guided, and arrival (remaining ≤ 150 m, or guidance cleared within
   300 m). Schema v3 adds `guided`, `arrived`, `planned_km`, `planned_s`,
   `guided_km`, `guided_s`. Shown as ANDROID AUTO / PLANNED / GUIDED stats and a
   "Took 25 min against Android Auto's 18 min estimate" insight (±15%).
   **Route, stops and totals** — *implemented; JVM + node tested,
   emulator-checked, not on the car.*
   - **Route kept forever.** Schema v4 adds `trips.route`: up to 300 GPS points
     `[lat, lon, kmh, kw, fuelMode, fuelRate]` saved at close (and backfilled by
     the map sweep for trips that still hold samples), so the route outlives the
     30-trip sample retention. The snapshot is now **tiles only** and stores its
     frame (`snapshot_view` = `z,ox,oy`); the page draws the route over it as SVG
     (`_energyOverlayFrame`), coloured by SPEED / ELECTRIC / FUEL
     (`_energyRouteSegments`, same bands on the live map). v4 resets
     `snapshot_path` so old snapshots with a baked route are redrawn.
   - **Refuel / charge stops.** `TripEngine` compares `remain_fuel_percentage`
     (≥ 4 pts → litres = Δ% × 55 / 100) and SOC (≥ 8 pts, in %) across a
     READY-off stop, settling for 3 min after setting off; a change noticed while
     no trip is open attaches to the next trip. Table `trip_stops`; markers on
     both maps, tap for litres / % and levels.
   - **ALL TIME** in HISTORY (`TripBridge.getTotals`): electric vs engine
     distance split, refuels, and range estimates (`_energyRangeEstimates`): per
     tank = recorded km/L × 55 L (and the car's `fuel_mode_remain_odometer` /
     fuel %), per charge = the car's EV range / SOC, or measured usable capacity
     (net kWh / ΔSOC over ≥ 3 trips with ≥ 10 pts) / net kWh per EV km.
   - One toggle per unit; source label hidden while recording normally; trip
     rows lost the economy figure so the map is wider.
   **Popup layout, language and units** — *emulator-checked at 1920x720.*
   - Every ENERGIA text is PT-BR (tabs, stats, insights, legends, stops, notes,
     rail card, catalog). Numbers keep the dot decimal the rest of the app uses.
   - UNIDADES button in the popup header (left of ×) opens a sheet with explicit
     choices: km/L | L/100 km and kWh/100 km | km/kWh.
   - No scrolling: the tab bar carries MAPA | DADOS plus the trip title (VIAGENS)
     or GRÁFICO | DADOS plus the period (HISTÓRICO), and the chosen pane fills
     the popup. The snapshot is an `<image>` inside the route svg
     (`preserveAspectRatio` meet), so tiles, route and ⛽ / ⚡ pins letterbox
     together at any pane size. DEMO moved to the header badge.
   **Maps and the wide workspace**
   - The VIAGENS map pane is the interactive OSM map (`_energyMapCreate`), so it
     fills the pane at any width; the tiles-only snapshot with the SVG route is
     the offline fallback (`navigator.onLine === false`). EXPLORAR MAPA and its
     overlay mode are gone.
   - A board widget 4x2 or wider (`_consumptionWidgetView`, `wide`) is the whole
     workspace: VIAGENS shows list + map + data and HISTÓRICO graph + data side
     by side, with no MAPA | DADOS / GRÁFICO | DADOS toggles. The 3x2 popup keeps
     its toggles (`_focusedCardRenderFields` passes `'popup'`). Popup and widget
     markup come from one template, so they cannot drift.
   **When a trip ends**
   - READY off (`driving_ready_state` "0" or "-1", read like Impulse's
     `isVehicleReadyStateOff`; any other value is READY) for 10 min
     (`MERGE_GAP_MS`), checked by the 1 s tick. READY back within 10 min keeps
     the same trip (a fuel stop).
   - The head unit slept with the trip open: `TripRecorder` sees its ticks stop on
     `SystemClock.elapsedRealtime()` (≥ 10 min) and calls
     `TripEngine.closeInterrupted()`, which ends the trip at its last sample; it
     then requests an Impulse snapshot so READY is current. A wall-clock gap is
     not used: a forward NTP/GNSS correction while driving keeps the trip.
   - The process restarted: `restore()` closes a checkpoint older than the gap.
   - Android Auto guiding and not yet within 2 × `ARRIVAL_M` of the destination
     (`guidingNow`): none of the rules above end the trip, however long the stop
     (a READY-on after it merges into the same trip). Once guidance ends
     (arrived or cancelled) the READY-off rule applies as usual, ending the trip
     at READY-off. Guidance silent for `GUIDANCE_HOLD_MAX_MS` (60 min,
     `TripState.lastNavT`) is a lost session and holds nothing.
   - FINALIZAR VIAGEM on the trip being recorded opens a confirmation dialog
     (CANCELAR / FINALIZAR); confirming calls `TripBridge.finishTrip()` →
     `finishNow()`, which closes it now (at READY-off if the car is already off);
     if READY is still on a new trip starts at that moment.
   - Logcat `H6Trip` now prints every raw READY value, so the next real drive
     shows what the car publishes around switch-off.
   **Map interaction and EV share**
   - Route map: in the pane it pans and pinches (two fingers, previewed by one
     transform, snapped to whole zoom levels on release); a tap opens it at full
     size over the whole workspace (`.hv-energy-mapmax`, `energyMapMax`), where
     + / − / fit and ⤡ live. ⤢ appears on the pane's map once it has been moved.
   - Popup ⤢ / ⤡ beside ×: the 3x2 frame or the board's full six-column width
     (`--hv-pop-wide-width`), remembered in `h6_energy_popup_wide`; maximised it
     is the wide workspace (no toggles, panes side by side).
   - EV share: electric distance over distance with the engine state known
     (`TripState.iceKm`), with the undetermined start shared out in proportion,
     expressed in odometer km. An all-electric drive read 99% because integrated
     EV km was divided by odometer km.
5. **Insights** — *implemented; desktop + emulator checked, not on the car.*
   - Per trip (TRIPS detail), up to three, strongest first
     (`_energyTripInsights`): economy vs your recent trips (fuel, or net
     electricity per km for an electric trip; ±8% or more, needs 3+ peers), EV
     share vs usual (±15 pts), hard stops / accelerations per 10 km vs your
     rate (1.5x or more), or "no hard acceleration or braking", idling ≥ 3 min
     and ≥ 20% of the trip, energy recovered, climb ≥ 60 m as context.
     Every sentence is a measurement or a comparison with the driver's own
     trips; **none claims a saving nobody measured.**
   - HISTORY: this month vs last month over the same days, clamped to last
     month's length (`_energyMonthCompare`); sign follows the displayed unit,
     tone says whether that direction is good.
   - HISTORY: best / worst fuel economy of the last 30 days, trips ≥ 3 km
     (`_energyBestWorst`); tapping one opens it in TRIPS.
   - The three methods are pure; `scripts/test-energy-insights.mjs` runs them
     against fixtures and against 8 source mutants, each of which a scenario
     must catch. Thresholds are provisional until real drives.
6. **Contract tests + docs** — negative-controlled
   `scripts/test-consumption-card-contract.mjs`; update `ui-surfaces.md` and
   `widget-data-audit.md`; interleaved perf A/B on the car.
7. **(Optional, future) Refuel log and fuel price** — below.

## AGORA and HISTÓRICO

- **AGORA** is four zones, each answering one question: INSTANTÂNEO (fuel and
  battery kW, each with a state chip), ÚLTIMOS 10 MIN (traction / regen kW
  areas with fuel L/100 km overlaid, plus the window averages), VIAGEM ATUAL,
  and NÍVEIS. The 7-day bars left AGORA.
  - Samples live in a 600 s ring (`_energySample`); the chart paths are
    painted in place by `_paintEnergyChart`, never through setState.
    `_energyTenMinView` is pure and tested (`scripts/test-energy-now.mjs`).
  - NÍVEIS bars show start against now (`_energyLevelView`): the base is the
    level now, a darker segment of the same colour is what the trip used, a
    green segment is what a refuel / recharge added above the start, and a
    tick marks the start. `TripStore.summaryJson` now carries
    `fuelPctStart` / `socStart` for the live trip.
- **HISTÓRICO** is three topics (DISTÂNCIA / COMBUSTÍVEL / ELETRICIDADE), the
  this-month-vs-last strip with best / worst kept here, and stacked electric /
  fuel bars. At 3x2 a topic lists label and value on one line and hides its
  extra rows; maximised it shows two columns per topic.
  - ABASTECIDO / RECARREGADO come from `TripBridge.getStopTotals(fromMs)`:
    how many `trip_stops` since the range start, and the level points they
    added. Litres = points x the version's tank; kWh = points x capacity.
  - Capacity is the measured usable figure from `TripStore.totalsJson` (net
    pack kWh over SOC points, trips moving SOC 10+, 3+ trips), else nominal.
  - VS WLTP: your km per charge (capacity / net kWh per EV km) as a share of
    the declared range. An estimate: 1% SOC steps, BMS buffers and temperature
    all move it, and energy drawn from the wall is higher than pack energy.
- **Specs by version** (`H6_ENERGY_SPECS`, Brazilian spec sheets): HEV 1.6 kWh
  / 60 L; PHEV19 19 kWh / 55 L / WLTP 115 km; PHEV34 and GT 34 kWh / 55 L /
  WLTP 170 km. **Impulse still assumes 55 L for every version**; that is
  tracked separately, so HEV litres differ between the two apps.
- **Icons** are Material Icons paths (Apache 2.0) in `H6_ENERGY_ICONS`, never
  drawn glyphs: add / remove / fit_screen / fullscreen / fullscreen_exit on the
  map, open_in_full / close_fullscreen on the popup, local_gas_station /
  ev_station for stops.

## The trip being recorded, live

VIAGENS draws the open trip as it happens: the route grows, coloured by the
chosen metric, ends at a car marker, and refuels / charges appear when they
are detected. DADOS, the list row and the header repaint each second.

- **Native.** `TripBridge.getTripPointsSince(startMs, afterT, max)` returns
  the open trip's points after `afterT` plus its stops. Points are saved in
  batches of 30, so the recorder also returns what it still holds in memory:
  the route is a second behind, not half a minute. The buffer is copied
  BEFORE the database is read and `flushPoints` saves a batch before it
  leaves the buffer, so a flush in between shows a point twice (dropped by
  time) and never loses it. `pending` is guarded by itself because the
  bridge reads it from the JavaBridge thread.
- **Page.** `_energyLiveDetail` keeps one track per open trip and asks only
  for new points, at most once a second, and only while a live map or a live
  DADOS pane is mounted (`_energyLiveTripTick`). Past 3000 points the older
  half is thinned (`_energyTrackAppend`) so the SVG stays light on a long
  drive. The map is updated in place (`st.update`), never rebuilt, and
  nothing here commits to React except the one moment the route first has
  two fixes and the pane swaps its waiting note for the map.
- **View.** `_energyMapView`: a finished route is framed whole; the live one
  is framed whole while that is zoom 13 or closer (capped at 16), and past
  that follows the car at zoom 13. Panning or pinching stops following; the
  fit button ("Seguir o carro") resumes it. The live map shows offline too —
  tiles that fail hide and the route stays.
- **Not verified on the car yet**: GPS cadence, bridge cost of the per-second
  read on the MMI, and how the follow view feels at driving speed.

## Future (optional): refuel log and fuel price

- Detect a refuel as a jump in `remain_fuel_percentage` while parked; litres =
  delta % x the version's tank / 100 (`H6_ENERGY_SPECS`). Impulse uses 55 L
  for every version (`DASHBOARD_FUEL_TANK_CAPACITY_LITERS`); the HEV tank is 60 L.
- Store each refuel (time, place from `PlaceGlance`, litres, odometer).
- Look up the local fuel price from an online source and store it with the
  refuel, enabling cost per trip / per km. Candidate for Brazil: ANP's public
  weekly price survey by municipality — verify availability and terms first.
- Allow manual price entry when the lookup fails.
