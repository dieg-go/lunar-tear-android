#!/data/data/com.termux/files/usr/bin/sh
# Read-only recon of the Termux-side lunar-tear install.
# Writes a report to shared storage so the Android app's host tooling can read it
# via adb (Termux's own home directory is unreachable without root).
# Nothing is copied, moved or deleted.

OUT=/sdcard/lt-recon.txt
: > "$OUT" 2>/dev/null || OUT="$HOME/lt-recon.txt"
: > "$OUT"
exec >> "$OUT" 2>&1

ROOT="$HOME/lunar-tear-server"

echo "== date =="; date
echo "== HOME =="; echo "$HOME"
echo "== HOME listing =="; ls -la "$HOME"
echo "== $ROOT listing =="; ls -la "$ROOT"
echo "== du -sh $ROOT =="; du -sh "$ROOT"
echo "== depth-1 sizes =="; du -sh "$ROOT"/* 2>/dev/null | sort -h | tail -40
echo "== depth-2 sizes =="; du -sh "$ROOT"/*/* 2>/dev/null | sort -h | tail -40

echo "== marker files (size, path) =="
find "$ROOT" -maxdepth 6 \
  \( -name '20240404193219.bin.e' -o -name 'list.bin' -o -name '*.7z' -o -name 'database.bin' -o -name 'versions.lock.json' \) \
  -printf '%10s  %p\n' 2>/dev/null | head -60

echo "== assetbundle dirs =="
find "$ROOT" -maxdepth 7 -type d -name 'assetbundle' 2>/dev/null | head -10

echo "== assetbundle file counts (may take a while) =="
find "$ROOT" -maxdepth 7 -type d -name 'assetbundle' 2>/dev/null | while read -r d; do
  printf '%s\t%s files\n' "$d" "$(ls -1 "$d" 2>/dev/null | wc -l)"
done

echo "== revisions dirs =="
find "$ROOT" -maxdepth 6 -type d -name 'revisions' 2>/dev/null | head -10

echo "== termux storage symlink =="; ls -la "$HOME/storage" 2>&1 | head -20

echo "== /sdcard write test =="
if touch /sdcard/.lt-write-test 2>/dev/null; then echo YES; rm -f /sdcard/.lt-write-test; else echo NO; fi

echo "== free space =="; df -h /sdcard /data 2>&1

echo "== existing shared-storage asset trees =="
ls -la /sdcard/lunar-tear 2>&1
ls -la /sdcard/lunar-tear/assets 2>&1

echo "== done =="
echo "report written to $OUT"
