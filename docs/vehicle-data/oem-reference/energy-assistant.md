# OEM energy application (`com.beantechs.energyassistant`)

What the OEM energy application shows, which vehicle keys it reads, and what that does and does not
mean for this project. It comes from static inspection of the package (its resource table and class
names; the resources are name-obfuscated, the strings and classes are not). Nothing from the package
is distributed, and its presence on the head unit is **not** an API: it supplies no verified
consumption history to other apps.

## Screens

| Screen | Content | Relevant to the H6 HEV |
|---|---|---|
| Flow (one fragment each for HEV, PHEV, EV and fuel) | An architecture-specific flow diagram with 30+ named states ("Series driven (battery charging)", "Energy recovery + Driving charging"), SOC, instant energy and total range | Yes. The power card already covers most of it. |
| Trend | Per-day bars of fuel L/100 km, kWh/100 km, energy recovered and distance. Last 7 days, last 30 days or a custom range, with month paging; gated ("available when you drive 10 km / 7 days"); "since last charge" | Yes |
| Charging | Scheduled charging, SOC limit, current, estimated time | No (no plug on the HEV) |
| Discharge | V2L and V2V | No |
| Tips | Ten static, generic tips | The static-tip pattern is what the ENERGY workspace replaces |

## Flow states and graphs

Resources `flow_name_value_38` to `46` distinguish front regeneration, rear regeneration, both
motors driving, rear driving, front driving, engine propulsion while charging or discharging, and
external slow and fast charging. They explain more than a single EV/hybrid label. Graph resources
offer 10, 50 and 100 km and 7 and 30 day views, electricity, fuel and power series, and
since-last-charge summaries.

A background service listens to the car and writes a local database with per-day consumption
(date, mileage, fuel, electricity, recovery), mileage buckets, cumulative totals and per-charge
cycle tables. That establishes an OEM history feature, not an accessible history API; the
records are private to the OEM application.

## Keys it reads that the viewer does not

`car.ev_info.energy_recovery_info`, `car.ev_info.total_odometer`,
`car.ev_info.last_charge.time_odometer`, `car.basic.kilometer_avg_fuel_consumption`,
`car.basic.kilometer_avg_elect_consumption`, `car.ev_info.avg_energy_consume_info_since_reset`,
`car.ev_info.average_energy_consume_info`, `car.basic.cur_journey_odometer`,
`car.ev_info.motor_power`, `car.configure.energy_type`, `car.unit_setting.cluster_system`.

Keys the OEM application reads but Impulse does not monitor never reach this app. Either add them
to Impulse's monitored properties (its Current Values screen, no code change) or do without:
regeneration energy is integrated from pack power anyway.

## Integration evidence

- SOC, voltage and current, remaining EV and fuel range and the companion flow packet are exposed by
  Impulse's theme bridge. `car.ev_info.energy_drive_state` and `car.ev_info.charging_state` are in
  the companion key list too.
- `car.ev_info.motor_power` is absent from the inspected viewer and companion mapping. It must not
  become a plausible-looking live motor-power readout.
- Impulse's flow mapper estimates all-wheel-drive activity in some unknown or idle cases, simplifies
  mixed engine and generation states and omits OEM states 43 and 44. Front and rear -1/0/+1 mean
  categorical activity, never measured torque or power. A future packet should carry the raw OEM
  enum and an observed-or-inferred flag before any finer traction claim is made.

## What this app does beyond it

The OEM application has no individual trips, no route, no breakdown of where energy went and no
advice derived from the drive itself. The ENERGY workspace adds those from the viewer's own
recording ([energy-workspace](../../features/energy-workspace.md)). Other driver questions, and
where they stand:

| Question | Status |
|---|---|
| Where is energy going now? | Implemented: independent front and rear activity, engine status and battery charge ([power-flow](../../features/power-flow.md)). |
| How much electrical demand did that acceleration create? | Implemented: the last 60 seconds of battery-power magnitude, with peak and gaps. |
| How is charge changing over this drive? | Next: a session SOC trend from real samples, separate from range estimates. |
| What happened while charging? | Next: charge state, elapsed time and SOC gain, once the raw charging-state semantics are confirmed. |
| How much braking energy did I recover? | Explore: integrate electrical power once the current polarity and the generation-versus-braking states are validated. |
| How much torque or grip does each wheel have? | Deferred: it needs torque or wheel data. Do not build a 50/50 gauge from categorical flow. |
| What is my 30-day consumption? | Answered by the viewer's own trip recorder, scoped to drives made while it was installed. |

Keep range forecasting in Range and drive and regeneration controls in Driving: Energy Flow stays a
glanceable explanation of the powertrain, with progressive detail in the popup.
