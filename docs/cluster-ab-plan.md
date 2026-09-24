# Cluster-lag A/B plan

Status: **planned, not yet run** (drafted 2026-09-23).

## Background

With Android Auto mirrored onto the cluster by Impulse, **Waze + the viewer on the
main screen** falls progressively behind: 30-40 km after 1-2 h. Waze alone is
fine, and so is Google Maps with the viewer. Three field notes from the
rafaelcs28 fork (2026-09-23) established this. The third measured Waze's stream
as ~1.6-1.8x heavier than Maps on the AA host's reader/decoder threads.

The AA host never drops a late frame, so any shortfall accumulates. The
question is what the viewer takes away from it.

**Working hypothesis, not yet measured:** the shared resource is SurfaceFlinger.
One main thread composes both displays, and both flip into GPU (Client)
composition on their own. If so, the viewer costs the cluster roughly in
proportion to **how many frames it posts per second**, not to how much CPU each
frame costs. The GPU itself is idle here (`gl.finish` 0.1 ms, see CLAUDE.md).

## What each arm answers

| Arm | Setup | Question |
|---|---|---|
| **A** | viewer in front, widgets on, `__powerLean(false)` (old widget loop) | baseline |
| **B** | viewer in front, widgets on, `__powerLean(true)` (current code) | does the lean widget loop lower load? (B vs A) |
| **C** | viewer in front, **no widgets** (car only), lean on | does cutting the frames the widget loop posts help SurfaceFlinger? (C vs B) |
| **D** | a static screen in front (Android Settings) | floor: what the cluster gets with no viewer |

A and B differ in per-frame CPU. B and C differ mainly in frame count. That is
the split the hypothesis needs.

## Before the drive

1. **Build:** a debug APK from this branch. It needs to be debuggable for the
   CDP switch and for `H6Perf` (now at `Log.w`).
2. **Desktops:** desktop 1 is the usual board with **POWER and a graphs
   pane**. Without them, arms A and B are identical. Desktop 2 is empty (car
   only). Switching arm C is then one swipe.
3. **Probe on the car:**

   ```bash
   adb push scripts/cluster-ab-probe.sh /data/local/tmp/
   ```

   Over root telnet with no adb, paste the file with `cat > /data/local/tmp/cluster-ab-probe.sh`.
4. **Keep constant for the whole drive:** Waze **guiding a route**, on the AA
   cluster mirror; the same phone; x-ray off; the same day/night mode; nobody
   touching the main screen during a measurement window.
5. **Note the cluster lag at the start** (how far behind the phone's own screen
   the cluster is), and again at the end.

## Switching arms

| To | Do |
|---|---|
| A | desktop 1, then `node scripts/device-cdp.mjs eval "__powerLean(false)"` |
| B | desktop 1, then `node scripts/device-cdp.mjs eval "__powerLean(true)"` |
| C | desktop 2 (lean stays on) |
| D | `adb shell am start -a android.settings.SETTINGS`; return with the viewer's launcher tile |

Confirm A/B with `eval "__perf().powerLean"`, or in `adb logcat -s H6Perf`. The
flag persists across a WebView reload on purpose, so an arm cannot change
silently. **Check the `focus` column** in the probe output for every row: the
HOME key did NOT take the viewer out of focus in the fork's first attempt.

## Sequence

After every switch, **wait 60 s**, then run:

```bash
sh /data/local/tmp/cluster-ab-probe.sh <A|B|C|D> 3 20
```

That is 3 windows of 20 s each, about 70 s with overhead, so a block is ~2.5 min.

Run the arms **A B C D D C B A**, then repeat that once: 16 blocks, ~40 min of
driving. The mirrored order cancels a linear drift. This unit drifts ~2x over
minutes (see CLAUDE.md), so blocks taken far apart cannot be compared directly.

The arms are compared on **rates**, not on the accumulated backlog, so there is
no need to reset the projection between arms. The backlog carries over;
per-window rates do not.

**At the end, one extra observation.** If the cluster is visibly behind, switch
to D for 5 minutes and watch whether it **catches up** or only stops getting
worse. Catching up means the backlog drains when load drops, which points at
composition throughput.

## What the probe records

One CSV row per window in `/data/local/tmp/cluster-ab.csv`. `*_cpu` columns are
% of one core (8 cores = 800 %).

| Column | Meaning | Role |
|---|---|---|
| `sf_main_cpu`, `sf_main_wait` | SurfaceFlinger main thread: running, and runnable-but-waiting | **primary**: the hypothesis |
| `viewer_fps` | viewer hwui frames/s (`gfxinfo`) | **primary**: frame count |
| `aa_dec_out` vs `aa_reader` | decoded-frame wakeups vs packet wakeups, per second | **primary**: does the AA host keep up |
| `viewer_cpu`, `viewer_render_cpu` | viewer process, and its own WebView renderer | A vs B |
| `sf_all_cpu`, `impulse_cpu`, `cpu_busy_pct` | context | |
| `aa_dec_in`, `aa_mc_loop`, `aa_codec_looper`, `aa_dec_cpu` | the rest of the AA decode pipeline | context |
| `queued` | AA video layer queue depth at window end | secondary: accumulates, so it does not compare across arms |
| `comp_main`, `comp_cluster` | layers in Client (`c`) / Device (`d`) composition at window end, display 0 / 4 | secondary |
| `focus` | focused window | sanity check for every row |

`na` means the process or thread was not running. `x` means the process
restarted mid-window, so that cell is void.

Pull the results with `adb pull /data/local/tmp/cluster-ab.csv`. Send the CSV
plus the start/end lag and the catch-up observation.

## Reading the result

For each comparison, take the **paired** deltas within each A-B-C-D round (4
pairs per comparison). An effect counts only if it has the **same sign in at
least 3 of the 4 pairs**. Report the median delta, not the mean.

| Outcome | Conclusion | Next step |
|---|---|---|
| B < A on viewer CPU, SF and AA unchanged | the lean loop is a CPU win that does not help the cluster | keep it; frame count is the next suspect |
| C << B on `viewer_fps` **and** on `sf_main_*`, AA keep-up better | frame count is the lever | throttle the graph canvases (30-60 Hz today) and the power CSS animations whenever the 3D loop is also rendering, or a projection mode |
| SF flat across all arms, AA keep-up still worse than D | SurfaceFlinger is not the bottleneck | hypothesis dropped; look at memory bandwidth / the decoder; ask for a kgsl or devfreq reading |
| B ~ D on everything | the viewer is not what tips Waze over at this point | re-check the Waze-alone claim with the probe (arm D for a long stretch) |

## Probe caveats

- `dumpsys SurfaceFlinger` takes SurfaceFlinger's lock. The probe reads it once
  per window, after the end snapshot, so it does not land in the measured
  interval. `--latency` segfaults on this ROM.
- The `aa_*` wakeup rates are proxies for packets and frames, not exact fps.
  They are valid for comparing arms on the same pipeline.
- `viewer_fps` counts the viewer window's hwui frames, i.e. what the WebView
  hands to SurfaceFlinger. That is exactly the quantity the hypothesis is
  about.
- The ROM's `awk` segfaults on several ordinary constructs (listed in the
  script). Test any edit on the car before relying on it.

## Cleanup

- Run `__powerLean(true)`, or `localStorage.removeItem('h6_powerLean')`.
- Once the numbers are in, **delete the `_powerLean` switch** and the old path it
  preserves (marked TEMPORARY in `index.html`).
