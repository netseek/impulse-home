# Signals and limits

What each widget really reads from the vehicle, what is local or simulated, and what a card must
say about it. A UI value is called *vehicle* only when the installed bridge can receive that signal
or execute that specific action. First audited 2026-09-05 against the WebView bridge, the Android
manifest and the telemetry contract; the Climate and weather rows were brought up to date on
2026-10-02, when the climate controls and the forecast were already live.

Card vocabulary for source and freshness: `LIVE`, `PARTIAL`, `STALE`, `UNAVAILABLE`, and
`DEMO · SIMULATED · NOT VEHICLE`. A fallback must never look like a vehicle reading.

## What the bridge exposes

`TelemetryBridge` offers `getCarData(key)`, an allow-listed `setCarData(key, value)`, an
allow-listed `invokeVehicleCommand(command, value)` and `reportPerf(json)`. Reads and writes are
separate: a readable key does not authorise a command. Writes need the native allow-list, the
vehicle `READY` state where relevant, and a pending state in the UI.

## Widget by widget

| Widget | What is connected | What is simulated, local or unavailable | Required truthfulness behavior |
| --- | --- | --- | --- |
| Media | `MediaBridge` transport, notification-backed now-playing metadata, and the source app's icon and launchability from `PackageManager` | A missing track is an idle state, not vehicle media data. Media is never simulated, so this card has no DEMO form | Controls are disabled and `_mediaTransport` is a no-op unless both an active track and `MediaBridge` are present. The idle card says `MEDIA · NO TRACK` or `MEDIA ACCESS REQUIRED`; a shell with no bridge says `MEDIA UNAVAILABLE · NO MEDIA BRIDGE`. Only the three commands `MediaBridge` exposes are offered: no seek, shuffle or repeat. See [media-card](../features/media-card.md). |
| Modes | Selected mode-state keys are consumed; the native allow-list owns the supported body commands | A browser selection is local preview state; a native selection is only pending until the car sends a state update | Browser cards say `LOCAL PREVIEW · not a vehicle setting`. Android mode writes require `READY` plus the installed `TelemetryBridge.setCarData`; otherwise the controls are disabled and no bridge call is made. |
| Graphs | Battery voltage and current, fuel consumption and selected aggregate consumption signals from `carTelemetry` | `_graphDemoValue()` produces animated desktop fallback curves; derived EV consumption is an estimate from pack power and speed | Every fallback readout says `DEMO · SIMULATED`; it is not vehicle history. |
| Power | SOC, range, fuel level and `haval.power.flow` when published | `CAR_POWER_DEMO_PACKED` and the graph fallback animate when no bus signal exists | The source badge says `DEMO · SIMULATED · NOT VEHICLE` for the fallback, compact cards included. See [power-flow](../features/power-flow.md). |
| Climate | The `car.hvac.*` keys (power, fan speed, driver and passenger temperature, AUTO, A/C, SYNC, air-cycle, blower mode, defrost, comfort curve) are read and written through the bridge by way of Impulse. The outside temperature is `car.basic.outside_temp`. The four-day forecast and an outdoor PM2.5 figure come from Open-Meteo (the PM2.5 tile labels its source) | The desktop browser has no bridge and shows persisted local preview values. The forecast is the only part that needs the network | A value that has not arrived is shown as unavailable, never as an invented temperature. Writes are debounced (about 320 ms) before they are sent; the card follows the values the car publishes. |
| Energy (type `consumption`) | NOW: `instant_fuel_consumption` (vehicle) and pack kW. TRIP and HISTORY: trips recorded on the head unit by `TripRecorder`, read through `window.TripBridge` | The electric NOW figure is derived (pack kW divided by speed) and labelled `· EST`. A desktop without `TripBridge` shows `DEMO · SIMULATED · NOT VEHICLE` data | Trip and history figures are the viewer's own recording, labelled `RECORDED` / `RECORDING`, never presented as the car's trip computer. No trip yet reads `NO TRIP YET`; a trip that burned under 0.05 L shows no km/L. See [energy-workspace](../features/energy-workspace.md). |

## Climate and weather

The climate card is wired to the vehicle: its keys are the HVAC family above, and Impulse relays
the writes. The outside temperature is a vehicle signal (`car.basic.outside_temp`). The forecast and
an outdoor PM2.5 figure are the external dependency: they come from
[Open-Meteo](https://open-meteo.com/), the request carries only a position rounded to two decimals
(about 1 km) and no key, and the manifest requests `INTERNET` and the location permissions for it.
Nothing else about the car or the driver is part of that request.

## What leaves the head unit

Three kinds of request go out, all to open services without an account or an API key. Each one needs the
location permission except the map tiles, which only need the network. If the permission is denied the
weather and place-name requests are not made, and no route is recorded.

| Request | To | What it carries |
|---|---|---|
| Weather forecast and outdoor PM2.5 | `api.open-meteo.com`, `air-quality-api.open-meteo.com` | The position **rounded to two decimals** (about 1 km). Refreshed on a time limit, and again when the position moves by more than about 5 km. |
| Place names for trips and the navigation card | `nominatim.openstreetmap.org` (OpenStreetMap) | The **precise position** (six decimals) of the trip ends and of the idle card, with an identifying `User-Agent`. At most one request a second for the whole process; for the idle card, only after the car has moved at least about 120 m. |
| Trip map snapshots and the interactive map | `tile.openstreetmap.org` | The coordinates of the map tiles that cover the route, which reveal the area. Tiles are cached for 30 days; no bulk prefetch. |

The route, the altitude and every trip are stored only in the head unit's own database.

## Energy limitations

The car supplies no history or named periods. History exists because the viewer records it
(`TripRecorder`, SQLite on the head unit), so it only covers drives made while this app was
installed, and a trip is only as good as the recorder's integration of instantaneous values. Each
stored trip keeps the car's own trip-computer readings at both ends (`raw_json`) so the integration
can be checked against the car on real drives; that comparison has not been made yet.

The OEM energy application is a launch target, not an API contract: its presence supplies no
verified consumption history and must not be treated as a data or control capability. See
[oem-reference](oem-reference/README.md).

## The native rail

The bottom rail is drawn by native code from the `bottomCards` payload the page sends. Native code
only allows a small list of navigation actions; it never interprets a card as a vehicle command.
The fallback Climate and Consumption cards read a display-ready summary from that payload and route
to the web commands `openClimate` and `openConsumption`. Media uses `MediaNowPlaying`, backed by
MediaSession and projection sources, and its controls use no vehicle API. The native rail never
calculates fuel level, efficiency or trip history, and litres are derived the way Impulse derives
them (see *There is no fuel-litres signal* in
[vehicle-data-and-impulse](../engineering/vehicle-data-and-impulse.md)).

Before the page provides data, Climate shows `— °C · Fan — · AUTO —` and Consumption shows `—`;
those are unavailable states, not readings. A value-only change in a configured `bottomCards`
payload updates the existing text view; a rebuild is reserved for structural changes (id, title,
action, order or limit), which preserves the rail's scroll position during telemetry updates.

## Still to validate on a vehicle

1. Capture period metadata and history samples before adding any trip, day or month selector that
   the recorder does not itself produce.
2. Confirm that each mode control returns a state acknowledgement, then label its pending and
   unavailable states accordingly.
3. Verify the Power and Graphs source badges both in a parked, no-bus session and in a moving one.
