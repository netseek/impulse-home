#!/system/bin/sh
# Cluster-lag A/B probe. Runs ON the head unit (adb shell or root telnet).
# Plan and arm definitions: docs/cluster-ab-plan.md.
#
#   adb push scripts/cluster-ab-probe.sh /data/local/tmp/
#   sh /data/local/tmp/cluster-ab-probe.sh <arm> [windows=3] [secs=20]
#
# Appends one CSV row per window to /data/local/tmp/cluster-ab.csv and echoes it.
# Every counter is read at the start and end of the window and diffed; nothing
# is sampled mid-window, so the probe itself stays out of the measurement.
#
# The one heavy read is `dumpsys SurfaceFlinger` (queue depth + per-display
# composition). It takes SurfaceFlinger's state lock -- i.e. it competes with
# the thing being measured -- so it runs once per window, AFTER the end
# snapshot. `dumpsys SurfaceFlinger --latency` segfaults on this ROM; do not
# add it.
#
# Units: *_cpu columns are % of ONE core (top's convention; 8 cores = 800 %).
# aa_* wakeup columns are voluntary context switches per second -- a proxy for
# packets / decoded frames, not an exact fps (see the 2026-09-23 fork note).
# Arithmetic is all in awk: mksh's $(( )) is 32-bit and nanosecond counters
# overflow it.

ARM="${1:?usage: cluster-ab-probe.sh <arm> [windows] [secs]}"
WINDOWS="${2:-3}"
SECS="${3:-20}"
OUT=/data/local/tmp/cluster-ab.csv
TMP=/data/local/tmp/cluster-ab.$$
VIEWER=com.havalh6.viewer
IMPULSE=br.com.redesurftank.havalshisuku
AA=com.ts.androidauto

HEADER=ts,arm,win,secs,focus,cpu_busy_pct,sf_main_cpu,sf_main_wait,sf_all_cpu,viewer_cpu,viewer_render_cpu,impulse_cpu,viewer_fps,viewer_janky_ps,aa_reader,aa_dec_in,aa_dec_out,aa_mc_loop,aa_codec_looper,aa_dec_cpu,queued,comp_main,comp_cluster
[ -f "$OUT" ] || echo "$HEADER" > "$OUT"

# Sum of schedstat run-ns over every thread of a process.
proc_run() {
  [ -n "$1" ] && [ -d "/proc/$1" ] || { echo 0; return; }
  cat /proc/"$1"/task/*/schedstat 2>/dev/null | awk '{s += $1} END {printf "%.0f\n", s}'
}

# The WebView renderer that belongs to the viewer. Both the viewer and Impulse
# run a com.android.webview:sandboxed_process0, told apart only by the
# ProcessRecord's owning uid (u0a1000i1 = the viewer's u0_a1000).
viewer_renderer() {
  vpid=$(pidof $VIEWER)
  [ -n "$vpid" ] || return
  owner=$(stat -c %U /proc/"$vpid" | sed 's/_//')
  dumpsys activity processes 2>/dev/null \
    | sed -n "s/.*ProcessRecord{[^ ]* \([0-9]*\):com.android.webview:sandboxed_process0\/${owner}i.*/\1/p" \
    | head -1
}

snapshot() {
  sf=$(pidof surfaceflinger)
  aa=$(pidof $AA)
  awk '{print "t", $1}' /proc/uptime
  awk '/^cpu /{t=0; for (i=2; i<=NF; i++) t+=$i; print "cpu_total", t; print "cpu_idle", $5 + $6}' /proc/stat
  awk '{print "sf_main_run", $1; print "sf_main_wait", $2}' /proc/"$sf"/task/"$sf"/schedstat
  echo "sf_all_run $(proc_run "$sf")"
  echo "viewer_run $(proc_run "$(pidof $VIEWER)")"
  echo "render_run $(proc_run "$RENDER")"
  echo "impulse_run $(proc_run "$(pidof $IMPULSE)")"
  dumpsys gfxinfo $VIEWER 2>/dev/null | awk '
    /^Total frames rendered:/ {print "gfx_frames", $4}
    /^Janky frames:/          {print "gfx_janky", $3}'
  if [ -n "$aa" ]; then
    for t in /proc/"$aa"/task/*; do
      c=$(cat "$t"/comm 2>/dev/null)
      # comm is cut to 15 chars (DecodeInputThread -> DecodeInputThre). Not
      # every AA host build has the Decode* threads at all: the 2024 PHEV's
      # runs ReaderThread x2 + MediaCodec_loop + CodecLooper only.
      case "$c" in
        ReaderThread)       k=aa_reader ;;
        DecodeInput*)       k=aa_dec_in ;;
        DecodeOutput*)      k=aa_dec_out ;;
        MediaCodec_loop)    k=aa_mc_loop ;;
        CodecLooper)        k=aa_codec_looper ;;
        *) continue ;;
      esac
      awk -v k="$k" '/^voluntary_ctxt_switches:/ {print k, $2}' "$t"/status
      awk '{print "aa_dec_run", $1}' "$t"/schedstat
    done
  fi
}

w=1
while [ "$w" -le "$WINDOWS" ]; do
  RENDER=$(viewer_renderer)
  focus=$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus \
    | sed 's/.* \([^ ]*\/[^ ]*\)}.*/\1/; s/[{}]//g; s/,/;/g')
  snapshot > "$TMP.0"
  sleep "$SECS"
  snapshot > "$TMP.1"
  # queued-frames of the AA video layer on the cluster, and whether each
  # display's layers went to GPU (Client) or HWC (Device) composition.
  # The queue is pulled with grep, not awk's match(): that crashed on the car
  # the first time the AA layer was actually present.
  dumpsys SurfaceFlinger > "$TMP.dump" 2>/dev/null
  q=$(grep -A12 '^+ .*(SurfaceView - com.ts.androidauto' "$TMP.dump" \
    | grep -m1 -o 'queued-frames=[0-9]*' | sed 's/.*=//')
  echo "queued ${q:-na}" > "$TMP.sf"
  awk '
    /^Display [0-9]+ HWC layers:/ { d = $2 }
    /\| *Client *\|/ { c[d]++ }
    /\| *Device *\|/ { v[d]++ }
    END {
      print "comp_main", "c" c[0] + 0 "d" v[0] + 0
      print "comp_cluster", "c" c[4] + 0 "d" v[4] + 0
    }' "$TMP.dump" >> "$TMP.sf"
  rm -f "$TMP.dump"

  ts=$(date +%H:%M:%S)
  # This ROM's awk is fragile, all of these crash or mis-parse on the car:
  # user-defined functions reading a global array, ?:, !(k in a), and
  # assigning an empty string "" to an array element. Keep it to plain
  # if/else and positive `in` tests.
  row=$(awk -v ts="$ts" -v arm="$ARM" -v win="$w" -v focus="${focus:-na}" '
    FNR == 1 { f++ }
    f == 1   { a[$1] += $2; next }
    f == 2   { b[$1] += $2; next }
             { sf[$1] = $2 }
    END {
      s = b["t"] - a["t"]; ns = s * 1e9
      busy = ((b["cpu_total"] - a["cpu_total"]) - (b["cpu_idle"] - a["cpu_idle"])) \
        / (b["cpu_total"] - a["cpu_total"]) * 100
      # % of one core. "x" = the process restarted mid-window; "na" = not running.
      n = split("sf_main_run sf_main_wait sf_all_run viewer_run render_run impulse_run aa_dec_run", P, " ")
      for (i = 1; i <= n; i++) {
        k = P[i]
        # Test membership BEFORE reading b[k]: reading it creates the element.
        if (k in b) { x = (b[k] - a[k]) / ns * 100; if (x < 0) o[k] = "x"; else o[k] = sprintf("%.1f", x) } else o[k] = "na"
      }
      # Per second.
      n = split("gfx_frames gfx_janky aa_reader aa_dec_in aa_dec_out aa_mc_loop aa_codec_looper", R, " ")
      for (i = 1; i <= n; i++) {
        k = R[i]
        # Test membership BEFORE reading b[k]: reading it creates the element.
        if (k in b) { x = (b[k] - a[k]) / s; if (x < 0) o[k] = "x"; else o[k] = sprintf("%.1f", x) } else o[k] = "na"
      }
      printf "%s,%s,%s,%.1f,%s,%.1f,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
        ts, arm, win, s, focus, busy,
        o["sf_main_run"], o["sf_main_wait"], o["sf_all_run"],
        o["viewer_run"], o["render_run"], o["impulse_run"],
        o["gfx_frames"], o["gfx_janky"],
        o["aa_reader"], o["aa_dec_in"], o["aa_dec_out"], o["aa_mc_loop"], o["aa_codec_looper"],
        o["aa_dec_run"], sf["queued"], sf["comp_main"], sf["comp_cluster"]
    }' "$TMP.0" "$TMP.1" "$TMP.sf")
  echo "$row" >> "$OUT"
  echo "$row"
  w=$((w + 1))
done
rm -f "$TMP.0" "$TMP.1" "$TMP.sf"
