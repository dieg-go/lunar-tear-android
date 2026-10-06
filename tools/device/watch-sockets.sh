#!/system/bin/sh
#
# Samples the kernel socket tables once a second, tagged with the wall clock,
# so a client's short-lived connections (including failed ones stuck in
# SYN_SENT, and sockets closed by a remote peer) can be reconstructed after the
# fact. Written for the "client stops at 60% and never talks to our server
# again" investigation: the game keeps its connections for fractions of a
# second, and the server logs nothing at all, so the kernel is the only witness.
#
#   adb push tools/device/watch-sockets.sh /data/local/tmp/
#   adb shell "nohup sh /data/local/tmp/watch-sockets.sh 150 >/dev/null 2>&1 &"
#   ... exercise the client ...
#   adb shell "cat /data/local/tmp/sockets.log" | Out-File sockets.log
#
# $1 = seconds to watch (default 120)

SECONDS_TO_WATCH=${1:-120}
OUT=/data/local/tmp/sockets.log

: > "$OUT"
i=0
while [ "$i" -lt "$SECONDS_TO_WATCH" ]; do
    {
        echo "=== $(date +%H:%M:%S) sample=$i"
        grep -v '^  sl' /proc/net/tcp
        grep -v '^  sl' /proc/net/tcp6
    } >> "$OUT" 2>&1
    i=$((i + 1))
    sleep 1
done
echo "=== done" >> "$OUT"
