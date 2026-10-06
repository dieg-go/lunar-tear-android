#!/system/bin/sh
#
# Extracts only revision 0 of the asset dump, in place on the phone.
#
# The full dump is 48.8 GB: ~20.9 GB of that is revision 0 (the revision the
# client actually asks for) and the other ~28 GB is ~818 historical revisions'
# list.bin/info.json files, which a client pinned to revision 0 never touches.
#
# Runs detached (nohup) and logs to /data/local/tmp/7z-extract.log so progress
# can be read over adb without holding a shell open.

ARCHIVE=/sdcard/Download/resource_dump_android.7z
DEST=/sdcard/lunar-tear-full/assets
LOG=/data/local/tmp/7z-extract.log
BIN=/data/local/tmp/7zzs

mkdir -p "$DEST"

{
    echo "start: $(date)"
    echo "archive: $ARCHIVE ($(stat -c %s "$ARCHIVE" 2>/dev/null) bytes)"
    echo "dest: $DEST"
    echo "free before: $(df -k /sdcard | awk 'NR==2 {print $4}') KB"
} > "$LOG" 2>&1

"$BIN" x "$ARCHIVE" -o"$DEST" -y -bsp1 "revisions/0/*" >> "$LOG" 2>&1
RC=$?

{
    echo ""
    echo "exit=$RC at $(date)"
    echo "free after: $(df -k /sdcard | awk 'NR==2 {print $4}') KB"
} >> "$LOG" 2>&1
