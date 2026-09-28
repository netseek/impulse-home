#!/system/bin/sh
# Unattended viewer-vs-static A/B for the cluster-lag test (docs/cluster-ab-plan.md,
# arms C and D). Runs ON the head unit, so it survives adb dropping when the car
# leaves home Wi-Fi, and nobody has to touch the screen while driving.
#
#   adb push scripts/cluster-ab-probe.sh scripts/cluster-ab-alternate.sh /data/local/tmp/
#   setsid nohup sh /data/local/tmp/cluster-ab-alternate.sh [rounds=5] > /data/local/tmp/alt.log 2>&1 &
#
# One round is V S S V (V = viewer in front, S = Android Settings in front):
# the mirrored order cancels a linear drift within every round. Each block
# switches the main screen, waits SETTLE seconds, then runs the probe for
# WINDOWS x SECS. Rows land in /data/local/tmp/cluster-ab.csv, arm V or S;
# the probe's focus column confirms which app was really in front.
#
# Stop early:  touch /data/local/tmp/cluster-ab.stop
# The viewer is always put back in front on the way out.

ROUNDS="${1:-5}"
SETTLE=30
WINDOWS=4
SECS=20
DIR=/data/local/tmp
STOP=$DIR/cluster-ab.stop

front() {
  if [ "$1" = V ]; then
    am start -n com.havalh6.viewer/.MainActivity > /dev/null 2>&1
  else
    am start -a android.settings.SETTINGS > /dev/null 2>&1
  fi
}

rm -f "$STOP"
r=1
while [ "$r" -le "$ROUNDS" ]; do
  for arm in V S S V; do
    [ -f "$STOP" ] && break 2
    echo "$(date +%H:%M:%S) round $r arm $arm"
    front "$arm"
    sleep "$SETTLE"
    sh "$DIR/cluster-ab-probe.sh" "$arm" "$WINDOWS" "$SECS"
  done
  r=$((r + 1))
done
front V
echo "$(date +%H:%M:%S) done"
