#!/data/data/com.termux/files/usr/bin/bash
#
# Copies the lunar-tear asset tree out of Termux's private home directory into
# shared storage, where the Android host app can actually read it.
#
# Why this has to run inside Termux: /data/data/com.termux is not readable by
# adb (the F-Droid build is not debuggable) and no other app can open it, so the
# bytes can only be moved by a process running as Termux's own uid.
#
#   bash /sdcard/lt-copy-assets.sh
#
# It only ever writes to /sdcard/lunar-tear-full. Nothing in Termux is modified
# or deleted. Safe to re-run: a finished destination is left alone.

set -u

SRC_HOME="${HOME}/lunar-tear-server"
DEST=/sdcard/lunar-tear-full
PATCHED_MASTER=/sdcard/lunar-tear/assets/release/20240404193219.bin.e
LOG=/sdcard/lt-copy.log
MARKER="$DEST/.copy-complete"

say() { echo "$@" >&2; }
have() { command -v "$1" >/dev/null 2>&1; }

if [ -w /sdcard ]; then
    exec > >(tee -a "$LOG") 2>&1
else
    say "!! /sdcard is not writable from Termux."
    say "!! Run 'termux-setup-storage' first, grant the permission, then re-run this script."
    exit 1
fi

echo "=== $(date) lunar-tear asset copy ==="

if [ ! -d "$SRC_HOME" ]; then
    echo "!! $SRC_HOME does not exist."
    echo "!! Top of \$HOME for reference:"
    ls -la "$HOME"
    exit 1
fi

if [ -f "$MARKER" ]; then
    echo "== destination already complete ($MARKER) =="
    du -sh "$DEST"
    echo "== nothing to do =="
    exit 0
fi

echo
echo "== source =="
echo "   $SRC_HOME"
du -sh "$SRC_HOME" 2>/dev/null
echo
echo "== depth-1 sizes =="
du -sh "$SRC_HOME"/* 2>/dev/null | sort -h | tail -30
echo
echo "== depth-2 sizes =="
du -sh "$SRC_HOME"/*/* 2>/dev/null | sort -h | tail -30

echo
echo "== candidate asset roots (a revisions/*/list.bin marks one) =="
roots=""
while IFS= read -r lb; do
    [ -n "$lb" ] || continue
    case "$lb" in
        */assets/revisions/*) root="${lb%%/assets/revisions/*}" ;;
        */revisions/*)        root="${lb%%/revisions/*}" ;;
        *)                    continue ;;
    esac
    case " $roots " in
        *" $root "*) continue ;;
    esac
    roots="$roots $root"
    printf '   %-60s %s\n' "$root" "$(du -sh "$root" 2>/dev/null | cut -f1)"
done <<EOF
$(find "$SRC_HOME" -maxdepth 6 -name list.bin -path '*revisions*' 2>/dev/null)
EOF

if [ -z "$roots" ]; then
    echo "!! No asset root found under $SRC_HOME (no revisions/*/list.bin)."
    echo "!! Dumping the tree so this can be pointed at the right place:"
    find "$SRC_HOME" -maxdepth 4 -type d 2>/dev/null | head -60
    exit 1
fi

# Pick the root with the largest assetbundle directory: that is the complete dump.
BEST=""; BEST_BYTES=0
for root in $roots; do
    bytes=$(du -sk "$root" 2>/dev/null | cut -f1); bytes=${bytes:-0}
    if [ "$bytes" -gt "$BEST_BYTES" ]; then BEST="$root"; BEST_BYTES="$bytes"; fi
done

echo
echo "== chosen source root =="
echo "   $BEST  ($((BEST_BYTES / 1024)) MB)"
ls -la "$BEST" 2>/dev/null
if [ -f "$BEST/assets/release/20240404193219.bin.e" ]; then
    ls -la "$BEST/assets/release/"
elif [ -f "$BEST/release/20240404193219.bin.e" ]; then
    ls -la "$BEST/release/"
fi
echo "   assetbundle files: $(find "$BEST" -maxdepth 6 -type d -name assetbundle -exec ls -1 {} + 2>/dev/null | wc -l)"

AVAIL_KB=$(df -k /sdcard | awk 'NR==2 {print $4}')
NEED_KB=$(( BEST_BYTES + BEST_BYTES / 10 + 3 * 1024 * 1024 ))
echo
echo "== space =="
echo "   source:     $((BEST_BYTES / 1024)) MB"
echo "   needed:     $((NEED_KB / 1024)) MB (source + 10% + 3 GB headroom)"
echo "   available:  $((AVAIL_KB / 1024)) MB"
if [ "$AVAIL_KB" -lt "$NEED_KB" ]; then
    echo "!! Not enough room on /sdcard to copy this tree."
    echo "!! Options: free up space, or let the tree be MOVED instead (ask the agent first)."
    exit 2
fi

echo
echo "== copying to $DEST (this takes a while) =="
mkdir -p "$DEST" || { echo "!! cannot create $DEST"; exit 1; }

# Copy the whole root: an `assets/` tree lands as-is, a dump root (revisions/...)
# lands as revisions/... and the app can rename it in place afterwards (instant,
# same volume - see AssetLayout.relocateDumpRoot).
SRC="$BEST"
if have termux-wake-lock; then termux-wake-lock; echo "   wake lock held"; fi

cp -a "$SRC/." "$DEST/" &
CPID=$!
while kill -0 "$CPID" 2>/dev/null; do
    sleep 30
    echo "   ... $(du -sh "$DEST" 2>/dev/null | cut -f1) so far ($(date +%H:%M:%S))"
done
wait "$CPID"
RC=$?
if have termux-wake-unlock; then termux-wake-unlock; fi

echo
echo "== copy finished (exit $RC) =="
du -sh "$DEST" 2>/dev/null
ls -la "$DEST"

# A dump root has revisions/ at the top; make it the layout the app expects so
# no rename is needed later. Same volume, so this is instant.
if [ -d "$DEST/revisions" ] && [ ! -d "$DEST/assets/revisions" ]; then
    echo "== relocating revisions/ -> assets/revisions/ (instant rename) =="
    mkdir -p "$DEST/assets"
    mv "$DEST/revisions" "$DEST/assets/revisions" && echo "   ok"
fi

# The app hardcodes assets/release/20240404193219.bin.e. Prefer the already
# patched file from the existing fixture, else whatever the dump shipped.
if [ -d "$DEST/assets" ]; then
    mkdir -p "$DEST/assets/release"
    if [ -f "$PATCHED_MASTER" ] && [ ! -f "$DEST/assets/release/20240404193219.bin.e" ]; then
        echo "== seeding master data from the patched fixture =="
        cp -a "$PATCHED_MASTER" "$DEST/assets/release/" && echo "   ok ($(stat -c %s "$DEST/assets/release/20240404193219.bin.e") bytes)"
    fi
fi

if [ "$RC" -eq 0 ] && [ -f "$DEST/assets/revisions/0/list.bin" ]; then
    touch "$MARKER"
    echo
    echo "== COMPLETE =="
    echo "   asset root for the app: $DEST"
else
    echo
    echo "!! Copy is incomplete or the layout looks wrong; marker not written."
    echo "!! Re-run this script to continue (existing files are copied again)."
fi
echo "=== $(date) done ==="
