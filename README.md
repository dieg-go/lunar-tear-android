# Lunar Tear Android

An Android app that **hosts a [lunar-tear](https://github.com/Walter-Sparrow/lunar-tear) private
server on the device** (gRPC game server + Octo CDN) and **patches the game's own files**
so the client talks to it — the APK it was installed from and the encrypted master
data. Assets come from a folder you already have; nothing is bundled.

Research/preservation tool. It ships no game assets, no game APK and no master data.

## Status

| Phase | What | State |
|---|---|---|
| 0 | Toolchain, native cross-build, on-device diagnostics | **done** |
| 1 | Foreground service, server launcher, asset folder, logs | **done** |
| 2 | APK patcher (metadata, `libil2cpp.so`, manifest, smali surgery, align, sign, install) | **done** |
| 3 | Master-data patcher (Go port of `patch_masterdata.py`) | **done** |
| 4 | Auth server on-device + Facebook redirect (the client's boot blocker) | **done** |
| 5 | Polish: config/port consistency, log export, docs | **done** |

### The patcher is a port of the reference, verified against it and on-device

The client plays. Both halves of the patching are checked against the community's
own tools rather than against my reading of them:

| artifact | this app | `lunar-scripts` (via the colab notebook) |
|---|---|---|
| `global-metadata.dat` | `D8D39402…` | `D8D39402…` — byte-identical |
| `lib/arm64-v8a/libil2cpp.so` | `7CB3E65C…` | `7CB3E65C…` — byte-identical |
| `classes.dex`, every `com/facebook/**` class (1322 of them) | disassembled and diffed | **1322 identical, 0 differing** |
| master data, all 607 tables | `dump_masterdata.py` | **0 differing tables**, 32 changed by the patch |

and on the phone, with the APK this app patched from the original:

```
15:03:12  GamePlayService/CheckBeforeGamePlay   OK
15:03:13  NotificationService/GetHeaderNotification
15:03:18  GimmickService/InitSequenceSchedule   (124 sequences)
15:03:26  QuestService/UpdateMainQuestSceneProgress
```

Three notes for anyone reproducing this:

* **The DEX stage has to do the whole job.** The reference's `--auth-host` mode is
  five edits, not one: scoped string rewrites **plus** four structural changes to
  the SDK's login path. Doing only the strings leaves the client hanging after the
  title screen; that was the bug this rewrite fixes. See below.
* **A phone that dozes mid-load freezes the client**, and it looks exactly like a
  stall at 60% with "Failed to connect. Retrying." on screen. `svc power stayon
  true` (or a tap every minute) is needed for a long observation, and both this
  build and the reference build show the same retry dialog while playing happily
  behind it.
* The end-to-end patcher test runs the *same* Kotlin code on the host, so a host
  pass is meaningful evidence for the device path - and no test needs the phone.

### Master-data patcher: verified against the Python reference and on-device

`native/cmd/patch-masterdata` is a port of `lunar-scripts/patch_masterdata.py`.
Run against the same 7.8 MB `20240404193219.bin.e` as the reference, its output
decrypts to **the same bytes in all 607 tables** (32 tables are modified by the
patch, the rest are copied verbatim):

```
tables checked=607, differing=0, changed by the patch=32
blob sizes: python 7739174, go 7737207 (framing difference only)
```

The 592-byte blob difference is the LZ4 *framing* of rebuilt tables: Go's LZ4 and
python-lz4 choose different (equally valid) block encodings, so every later
table-of-contents offset shifts slightly. The client only ever sees decompressed
tables, so this is invisible to it.

Running it **on the phone** through the Patch tab produces output that is
**byte-identical to the host run** (SHA-256 `bc4735a39517cc4d…`, 7 761 296 bytes),
the file as it was found is kept as `20240404193219.bin.e.orig` (so if it was
already patched, that is what `.orig` holds - not the pristine dump), and the game
server loads the result (`master data loaded (607 tables)`). Patching an
already-patched file is a byte-exact no-op, so the button is safe to press twice.

Reproduce with:

```powershell
$env:LT_MASTER_ORIG='<original>.bin.e'; $env:LT_MASTER_PY='<python>.bin.e'; $env:LT_MASTER_GO='<go>.bin.e'
cd native; go test ./internal/ltmd -run Differential -v
```

### Why the client needed a local auth server, and what the DEX stage really has to do

Observed on the phone, in this order:

1. The client authenticates, fetches the catalog and the master data, then goes
   silent: no RPC follows `GetUserData`, no asset request reaches the CDN, and its
   gRPC/HTTP keep-alives sit idle.
2. Its only other traffic is three TLS connections to **Facebook**
   (`2a03:2880::/32`) and two to Google/Firebase, held open while it stalls; the
   whole login then repeats every ~100 s.
3. Master data is *not* the cause: the game's own cache
   (`Android/data/<pkg>/cache/mst-*`) shows it downloaded and cached **all three**
   variants - our Go patch (7 761 296 B), the pristine original (7 801 104 B) and
   the reference Python patch (7 763 264 B) - and stalled identically with each.
4. In airplane mode the same login succeeds over loopback, but the failure moves
   from ~100 s to seconds: the wait is an *internet* call, not us.

The community's patcher answers this with `--auth-host`: it runs `cmd/auth-server`,
which impersonates the OAuth dialog, `/me` and `/check-username`, and rewrites the
Facebook SDK's *smali* to point at it. This app does the same, without apktool:

* `liblt-auth.so` is that upstream auth server, cross-compiled like the others and
  started on `[::]:3000` *before* the game server, which is given `--auth-url` so
  its account-linking RPCs can validate tokens against it.
* The Facebook SDK edits happen in `classes.dex`, because the Java SDK's URLs
  (`facebook.com`, `https://graph.%s`, `m.%s`, the `Utility.URL_SCHEME` literal
  `https`, the Katana/Orca package probes) live in the DEX, not in
  `global-metadata.dat`. Only the Unity/C# half is in the metadata, and that half is
  **deliberately left alone** - see the note under the table below.

`--auth-host` is five edits in `lunar-scripts/android/patch_apk.py`, and four of
them are structural. All five are ported, because the string rewrites alone are not
enough to log in:

| # | Reference function | What it does |
|---|---|---|
| 1 | `patch_facebook_smali` | rewrites the domain/format strings, but only inside `smali*/com/facebook/**` |
| 2 | `bypass_cct_redirect_check` | `CustomTabUtils.getValidRedirectURI()` returns the default `fbconnect://cct.<pkg>` instead of `""` |
| 3 | `force_webview_only_login_behavior` | flips `LoginBehavior.NATIVE_WITH_FALLBACK`'s Katana/CustomTab/Lite/Instagram booleans off, leaving the in-app WebView path |
| 4 | `patch_customtab_use_plain_view_intent` | rewrites `CustomTab.openCustomTab` to a plain `Intent.ACTION_VIEW` |
| 5 | `patch_webdialog_request_overload` | adds the API-24 `shouldOverrideUrlLoading(WebView, WebResourceRequest)` overload to `WebDialog$DialogWebViewClient` |

Edits 3 and 5 are the ones that matter: Chrome 117+ silently drops app-launched
`http://` navigations to private IPs (HTTPS-Upgrade and Private Network Access),
while a WebView is governed by the app's own cleartext setting and still loads
them - but WebView 75+ only delivers the `fbconnect://` redirect that completes the
login through the API-24 overload, which the SDK does not implement.

Two consequences worth stating, because they are easy to get wrong:

* **The metadata's `facebook.com` is not rewritten.** `Constants.GraphUrlFormat`
  (`https://graph.{0}/{1}/Unity.{0}`) is a longer literal the C# SDK builds URLs
  from, and an in-place edit cannot change it. Rewriting only its `{0}` domain
  therefore produces `https://graph.<auth host>/…`, a host that can never resolve -
  breaking the login instead of redirecting it. The reference only rewrites that
  domain when the auth host fits in the 12 bytes of `facebook.com`, which the usual
  `127.0.0.1:3000` does not, and it warns when it skips. The redirect belongs in the
  Java half, and that is where it happens.
* **`[::1]:3000` is no longer needed.** It was only chosen to fit inside that
  metadata string. The smali values are reassembled by smali, so they are not
  length-constrained, and the default is now the community's own
  `127.0.0.1:3000`.

The on-device implementation does not hand-build those method bodies with dexlib2 -
that would mean reproducing registers, branch targets and exception handlers by
hand, which is exactly where a port drifts from its reference. Instead the same
tools apktool uses underneath:

1. baksmali disassembles **only** the classes that can be affected: the
   `com/facebook/**` classes that reference one of the literals being replaced,
   plus the four structural targets - 14 classes out of 1322, with apktool's
   `.locals`/sequential-label settings so the reference's own needles apply;
2. `SmaliPatches` applies the reference's needles and replacements **verbatim**;
3. smali reassembles those classes into a small DEX, whose class definitions
   replace the originals in the big one;
4. dexlib2's pool writer produces the final file, recomputing the header.

The needles are not retyped: `tools/gen-smali-patches.py` evaluates them out of the
reference's source with `ast` and generates `SmaliPatchData.kt`, and
`SmaliPatchesTest` fails if that file and the reference ever disagree.

Three DEX traps were hit *before* this rewrite, when the stage edited the string
pool in place; they are why it now goes through whole classes instead:

| Trap | What ART and dexdump said |
|---|---|
| Adler-32 must cover everything after the *checksum* field (offset 12), not after the 20-byte signature | `Bad checksum (9aabcc18, expected 90a0c3e1)` |
| The string-data section is walked **sequentially**, so shortening a string and zeroing its tail makes it read phantom zero-length items | `String longer than indicated size 0` |
| String items can be **shared** (another `string_id` pointing inside one), so re-packing must remap those ids as well, and `string_ids` must stay sorted by value | hit while re-packing by hand |

The port is held to the reference by a test that disassembles the client's Facebook
classes twice, applies the edits with the Kotlin port to one tree and with the
reference itself (`tools/reference-smali-patch.py`, which imports `patch_apk.py`) to
the other, and requires the two trees to be identical file for file. That is what
caught the two things a hand port gets wrong here: baksmali's default output style
(`.registers`, address labels) does not match apktool's, and its line endings follow
the platform, while Python's `open()` hides that by translating newlines.

## Verified on device

Everything below was observed on a moto g84 5G (Android 15, arm64-v8a) over adb,
not inferred:

* **The Go binaries exec from the APK.** `liblt-cdn.so --help` and
  `liblt-server.so --help` print their flag usage, run out of `nativeLibraryDir`
  (`/data/app/…/lib/arm64`, mode `-rwxr-xr-x`).
* **Migrations run on-device.** `liblt-migrate.so --db … --mode up` applied all
  17 goose migrations in 506 ms; the resulting `game.db` is 716 800 bytes and
  reports version `20260527181946`.
* **The full server stack runs in the foreground service.** Master data is
  parsed on the phone: 17 308 costumes, 2 839 weapons, 10 032 shop items,
  160 gacha entries, 5 907 character-board panels, then
  `gRPC server listening on [::]:8003` and
  `[admin] webhook listener on 127.0.0.1:8082`.
* **The CDN serves the real catalog from shared storage.**
  `GET /v1/list/1/0` returned exactly 26 721 218 bytes (the real rev-0
  `list.bin`) with the resource base URL rewritten to
  `http://127.0.0.1:8080/rrrrrrrrrrrrrrrrrrrrr` (43-char padding), and
  `HEAD /assets/release/1/database.bin` returned 7 801 104 bytes.
* **The auth server runs on the phone and impersonates Facebook.**
  `liblt-auth.so` binds `[::]:3000`; from the device,
  `curl http://[::1]:3000/check-username?username=probe` returns
  `{"exists":false}`, `/me` with a bogus token returns 401, and
  `/v14.0/dialog/oauth?…` returns the bundled `login.html` (5 308 bytes). The smoke
  test checks all three, and the game server is started with
  `--auth-url http://127.0.0.1:3000`.
* **The client boots, logs in and plays.** With the smali surgery in place the
  patched client reaches the title screen and then plays the main quest against the
  phone: after `GetUserData` it continues with `GameStart`,
  `CheckBeforeGamePlay`, `GetHeaderNotification`, `InitSequenceSchedule`
  (124 sequences) and a stream of `UpdateMainQuestSceneProgress` calls, with the
  3D scene rendered and the AUTO battle button live. Before the fix it never got
  past `GetUserData`. (The client also shows a "Failed to connect. Retrying."
  dialog while it plays; the reference build shows exactly the same one.)
* **The APK is patched on the phone.** A 263 571 029-byte client was patched,
  re-zipped, signed and verified on-device: `global-metadata.dat` 4/4 strings,
  `libil2cpp.so` 13/13 sites, `usesCleartextTraffic` added, then
  `dex (facebook sdk): 14 facebook classes rewritten, shouldOverrideUrlLoading=applied,
  openCustomTab=applied, LoginBehavior=applied, getValidRedirectURI=applied`,
  `patched OK`. The whole run takes ~4.5 minutes, of which the smali step is ~3.5 -
  baksmali and smali are pure Java and the phone is roughly ten times slower than
  the host, which is still far cheaper than apktool's "disassemble all 10 000
  classes and reassemble them".
* **The phone's output verifies with Google's own tools**: `zipalign -c -v 4`
  reports "Verification succesful", `apksigner verify` reports *Verifies* with
  the v2 and v3 schemes and the `CN=Lunar Tear Debug` signer, and `aapt2` shows
  `usesCleartextTraffic(0x010104ec)=true` with versionCode 152 / versionName
  3.7.1 preserved.
* **The phone's output matches the community's patched APK.** Pulling the APK the
  app produced on the device and comparing it with the one the colab notebook
  produced: identical `global-metadata.dat`, identical `libil2cpp.so`, and all
  1322 `com/facebook/**` classes identical when both are disassembled and diffed.
  The remaining differences are packagings, not patches: this app keeps the
  original DEX entries and sets `usesCleartextTraffic`, while apktool rebuilds
  every dex, adds an unused `assets.dex`, leaves the original `META-INF` v1
  signature files behind and points at a `network_security_config` instead.
* **The whole flow was walked end to end in one sitting**: install -> All-files
  access -> asset folder -> *Start server* -> *Patch APK* -> update the game in place
  -> *Extend to 2030* -> *Reload server* -> play, ending with the same account
  (`cc62ded4-...`, `game_start_datetime` bumped) and the 3D scene running. Two
  defects fell out of that walk and are fixed: the tab row was under the status bar
  and unreachable, and the master-data reload was blocked by the cleartext policy.
* The service holds a wake lock and a Wi-Fi lock while running and releases both
  when stopped.

## Provisioning the asset tree

The app serves whatever folder you point it at (All-files access, no copying into
app storage). It needs this shape:

```
<root>/assets/release/20240404193219.bin.e      master data (AES + msgpack + LZ4)
<root>/assets/revisions/0/list.bin              the Octo catalog
<root>/assets/revisions/0/assetbundle/…         243 221 sharded bundles
<root>/assets/revisions/0/resources/…           movies, tags, support tables
<root>/assets/revisions/0/info.json             revision metadata
```

A dump root straight from an extractor has `revisions/` at the top instead of
`assets/revisions/`. `AssetLayout.relocateDumpRoot` renames it in place — same
volume, so it is a metadata operation, not a 21 GB copy.

### Extract revision 0, not the whole dump

Measured from a real Android dump (`resource_dump_android.7z`, 14 928 186 871 bytes
compressed):

| selection | uncompressed | entries |
|---|---|---|
| `revisions/0` — everything the client asks for | **20 907 771 934 B (20.9 GB)** | 201 073 files / 42 421 folders |
| the entire archive | 48 791 631 699 B (48.8 GB) | 245 947 |

The other ~28 GB is **~818 historical revisions**, each carrying its own `list.bin`
(~22.7 MB) and `info.json` (~23-33 MB). A client pinned to revision 0 never reads
them, so extracting revision 0 alone is the difference between "does not fit on
the phone" and "fits with room to spare". Revision 0 itself breaks down as
15.27 GB `assetbundle` + 5.61 GB `resources` + 26.7 MB `list.bin`.

One trap worth knowing: this client asks for revision **817**, not 0, and the
dump's later revisions carry only *stubs* (`revisions/817/list.bin` is 93 bytes).
The CDN therefore falls back to revision 0's catalog and logs
`list request revision=817 canonicalized to revision=0` - which is correct
upstream behaviour, not a workaround, and extracting 817's stub changes nothing.

### With 7-Zip, on the phone, without Termux

`tools/device/extract-rev0.sh` does the extraction on the device. It leans on the
official 7-Zip Linux **static arm64** build (`7zzs` from `7z2603-linux-arm64.tar.xz`),
whose static ELF runs unchanged on Android — the same trick as the app's own Go
binaries, minus the packaging:

```powershell
adb push 7zzs /data/local/tmp/7zzs; adb shell chmod 755 /data/local/tmp/7zzs
adb push tools\device\extract-rev0.sh /data/local/tmp/
adb shell "nohup sh /data/local/tmp/extract-rev0.sh >/dev/null 2>&1 &"
adb shell "tail -c 300 /data/local/tmp/7z-extract.log"     # progress
```

`/data/local/tmp` matters: `/sdcard` is mounted `noexec`, so the binary cannot live
next to the archive. The script writes the `assets/` layout above directly, so no
rename step follows. Budget 25-40 minutes for 21 GB — the run is bound by FUSE
writes across 243 000 files, not by LZMA2 decode (the phone stayed ~80% idle CPU
and the effective rate climbed from 2.8 MB/s on small files to ~8 MB/s and up once
it reached the large ones). `tools/device/wait-for-extract.ps1` blocks until the
log reports `exit=` and then prints the result, which is convenient as a
background job.

`tools/device-smoke.ps1` defaults to a deliberately tiny fixture root
(`/sdcard/lunar-tear`: real `list.bin` + master data + 3 bundles) so the smoke test
stays fast; pass `-AssetRoot /sdcard/lunar-tear-full` to exercise a real tree.

## How it fits together

```
┌─────────────────────────── Android device ───────────────────────────┐
│  Lunar Tear app                                                      │
│    ├── foreground service ── exec ──▶ liblt-cdn.so    (Octo CDN :8080)│
│    │                          ├────▶ liblt-auth.so   (auth    :3000)  │
│    │                          └────▶ liblt-server.so (gRPC    :8003)  │
│    ├── liblt-migrate.so      applies the 17 SQLite migrations         │
│    └── patcher                rewrites the game APK, signs, installs  │
│                                        │                              │
│  your asset folder ── assets/revisions/0/… ──▶ CDN serves it          │
│  NieR Re[in]carnation 3.7.1 (patched) ──▶ 127.0.0.1:8003 / :8080       │
│  …its Facebook SDK ──▶ 127.0.0.1:3000 (the auth server impersonates it)│
└───────────────────────────────────────────────────────────────────────┘
```

The auth server is what the client's Facebook SDK is rewritten to, and the game
server validates account-linking tokens against it (`--auth-url`). `:3000` is
upstream's default port.

The Go servers are ordinary ELF executables packaged as `lib*.so`. Android extracts
those into `nativeLibraryDir`, the only location an app may execute from on
Android 10+ (app data directories are mounted `noexec`).

## Tuning the server (gacha odds, prices, pity)

Upstream compiles the gacha's odds, prices and pity into the binary, so changing
them means rebuilding it. The bundled `liblt-server.so` is therefore built from a
fork that carries one addition ([`dieg-go/lunar-tear`](https://github.com/dieg-go/lunar-tear)
at `92f1642`, pinned in `versions.lock.json`): those values are now defaults that
an optional `tunables.json` overlays at startup.

On the phone the file goes in the asset folder — the server's working directory,
so it sits next to `assets/` — and any file manager can edit it:

```
/sdcard/lunar-tear/tunables.json
```

```json
{
  "gacha": {
    "costumeSSRWeight": 500,
    "weaponSSRWeight": 500,
    "costumeSRWeight": 500,
    "weaponSRWeight": 1000,
    "weaponRWeight": 7500,
    "featuredRateUpPercent": 50,
    "pityCeilingCount": 100,
    "premiumSinglePullPrice": 0,
    "premiumMultiPullPrice": 0
  }
}
```

Weights are relative, not percentages: they are divided by their sum, so
upstream's `200/300/500/1000/8000` out of 10000 is 2% costume SSR, 3% weapon SSR,
5% costume SR, 10% weapon SR and 80% weapon R. With no file at all — and with an
empty one — every value keeps its built-in default, so the app behaves exactly as
it did before until you write one.

The startup log reports which of the two happened and what the effective odds
are, which is how you confirm it on the phone. Observed on the host against the
real master data, once with no file and once with the tuned file above:

```
tunables: no tunables.json, using built-in defaults (SSR 5.00% (costume 2.00%, weapon 3.00%), SR 15.00%, featured rate-up 35%, pity 200, single 300 gems, multi 3000 gems for 10, step-up boost 1.50x/2.00x)
tunables: loaded tuned.json (SSR 100.00% (costume 50.00%, weapon 50.00%), SR 0.00%, featured rate-up 100%, pity 1, single 0 gems, multi 0 gems for 10, step-up boost 1.50x/2.00x)
```

The same two runs were then repeated on the phone — APK installed over the
existing one, `tunables.json` written next to `assets/`, server started from the
app — and the app's own session log reports the same thing, first with an extreme
file and then with the saner one that is the example above:

```
09:52:29 [game] tunables: loaded tunables.json (SSR 100.00% (costume 50.00%, weapon 50.00%), SR 0.00%, featured rate-up 35%, pity 1, single 0 gems, multi 0 gems for 10, step-up boost 1.50x/2.00x)
09:52:37 [game] tunables: loaded tunables.json (SSR 10.00% (costume 5.00%, weapon 5.00%), SR 30.00%, featured rate-up 50%, pity 100, single 0 gems, multi 0 gems for 10, step-up boost 1.50x/2.00x)
```

What that proves is that the file is found, parsed and applied where the server
runs. The numbers reaching an actual pull are covered by the unit tests instead:
the draw honours the weights, and `Apply` writes the prices and pity that the
banner catalog is built from at startup.

A mistyped key or an out-of-range value **stops the server** with the field named
— `tunables: parse: json: unknown field "pityCeiling"` — rather than quietly
running on the defaults. The file is read once at startup, so *Reload server* in
the app is what applies an edit; a UTF-8 byte order mark (Notepad, PowerShell
`Set-Content -Encoding utf8`) is tolerated.

Upstream's README documents every field. Rebuilding the binaries is
`tools/build-native.ps1`, which also regenerates `NativeManifest.kt` so the
Diagnostics tab still reports what is actually installed.

## Prerequisites

* Windows with PowerShell 5.1+
* JDK 17+ (`JAVA_HOME` or a standard install location)
* Go 1.25+
* A checkout of [`lunar-tear`](https://github.com/Walter-Sparrow/lunar-tear)
  (default `C:\Users\diego\lunar-tear`, override with `LUNAR_TEAR_SRC`)
* The `lunar-scripts` checkout, for reference/differential tests
  (default `C:\Users\diego\lunar-scripts`)

Scripts must run with an execution-policy bypass because they are unsigned:

```powershell
powershell -ExecutionPolicy Bypass -File tools\bootstrap-sdk.ps1      # Android SDK into tools\.cache
powershell -ExecutionPolicy Bypass -File tools\bootstrap-gradle.ps1   # Gradle + wrapper
powershell -ExecutionPolicy Bypass -File tools\gen-proto.ps1          # server/gen stubs
powershell -ExecutionPolicy Bypass -File tools\gen-manifest-attrs.ps1 # aapt2-derived attribute ids
powershell -ExecutionPolicy Bypass -File tools\gen-keystore.ps1       # signing key (gitignored)
powershell -ExecutionPolicy Bypass -File tools\update-lock.ps1        # versions.lock.json
powershell -ExecutionPolicy Bypass -File tools\build-native.ps1       # jniLibs + NativeManifest.kt
powershell -ExecutionPolicy Bypass -File tools\gradle.ps1 assembleDebug
```

### Signing a release: the key you must never lose

Android identifies an app by its signing key, not by its name. That makes this key a
one-way door. An APK signed with a different key cannot be installed over one that is
already out, so the only way for an existing user to move onto it is to uninstall
first — and uninstalling takes the host app's `files/db/game.db` with it, i.e. every
account, quest and pull (see the note under *From installing the app to playing*
below).

So `assembleRelease` **refuses to package anything** until a real key exists, instead
of quietly falling back to the throwaway debug key:

```powershell
powershell -ExecutionPolicy Bypass -File tools\gen-release-keystore.ps1
```

That writes the keystore to `%USERPROFILE%\.lunar-tear\lunar-tear-release.jks` —
outside the repository on purpose, so no `.gitignore` mistake can leak it — with a
random password, and puts the matching `keystore.properties` (gitignored) in the
repository root for the build to read. It prints the certificate fingerprint: record
it, and back up both files. Losing the key *after* a release is the one mistake that
costs every user their save; losing it before is free.

`keystore.properties.example` documents the format. Three traps worth knowing:

* **Use forward slashes in `storeFile`.** `java.util.Properties` reads `\` as an escape
  character, so `C:\Users\…` loads as `C:Users…` and the build fails with a
  keystore-not-found error that looks like a path bug.
* **The opt-in property has no dot in its name.** PowerShell splits an unquoted
  `-Plt.…=true` at the dot, and Gradle then reports the second half as an unknown task.
* **The debug key still exists, in the cache.** `tools/_env.ps1` points
  `ANDROID_USER_HOME` into `tools/.cache`, so AGP's auto-generated debug key lives at
  `tools/.cache/android-user/debug.keystore` rather than `~/.android`. That is the key
  every release was silently signed with before this, and clearing `tools/.cache`
  replaces it with a new random one.

```powershell
# Local packaging smoke test only - never ship what this produces.
powershell -ExecutionPolicy Bypass -File tools\gradle.ps1 assembleRelease -PallowDebugSignedRelease=true
```

Bump `versionCode` in `app/build.gradle.kts` for each release you ship.

Tests (they use the real game files and skip themselves without them):

```powershell
# Extracts global-metadata.dat, libil2cpp.so and AndroidManifest.xml from the
# client APK into tools\.cache\corpus and regenerates the pre-image table.
powershell -ExecutionPolicy Bypass -File tools\extract-corpus.ps1
powershell -ExecutionPolicy Bypass -File tools\gradle.ps1 :app:testDebugUnitTest

# Master-data port vs the Python reference (needs a python with pycryptodome,
# msgpack and lz4; see the differential section above for how to produce the files).
cd native; go test ./... ; go test ./internal/ltmd -run Differential -v

# Smali-port helpers (python 3; LUNAR_SCRIPTS / LUNAR_PYTHON override the paths):
python tools\gen-smali-patches.py        # regenerate SmaliPatchData.kt from patch_apk.py
python tools\dump-reference-smali.py     # dump the reference's needles for inspection
python tools\reference-smali-patch.py <lunar-scripts> <smali-tree> <auth-host>
                                         # run the reference itself over a tree
```

`SmaliPatchesTest` uses those three: it disassembles the client's Facebook classes,
patches one tree with the Kotlin port and one with the reference script, and fails
on any difference; it also fails if `SmaliPatchData.kt` and `patch_apk.py` disagree.

Device smoke test — installs, starts the stack, and checks every step from the
device's own output, exiting non-zero on failure:

```powershell
powershell -ExecutionPolicy Bypass -File tools\device-smoke.ps1
powershell -ExecutionPolicy Bypass -File tools\device-smoke.ps1 -SkipInstall -PatchMasterData
```

It covers: device authorised, APK installed, bundled binaries extracted and
executable, migrations applied, CDN started, master data loaded by the game
server, gRPC listening, all three server processes alive, the CDN serving both the
Octo catalog and the master data over real HTTP, and the auth server answering
`/check-username` (200), rejecting a bad token (401) and serving the fake OAuth
dialog. 14 checks; the session log is pulled to `tools\.cache\logs\` for
inspection.

With `-GrpcProbe` it also runs `tools/grpc-probe`, a real gRPC client built on the
server's own generated stubs, and asserts the answers a patched client depends on:

```
server answered GetReviewServerConfig:
  api        127.0.0.1:8003
  octo       http://127.0.0.1:8080
  webView    http://127.0.0.1:8080
  masterData urlFormat=http://127.0.0.1:8080/master-data/%s
  [PASS] api hostname = 127.0.0.1
  [PASS] api port = 8003
  [PASS] octo url = http://127.0.0.1:8080
```

The probe lives outside the app's Go module because it needs the server's
generated protobuf stubs, which are gitignored in the upstream checkout.

## From installing the app to playing, in order

This is the whole path, and every step was walked on the phone (moto g84,
Android 15) in one sitting:

1. **Install the app** - `adb install app\build\outputs\apk\debug\app-debug.apk`, or
   copy the APK to the phone and tap it. Nothing is requested at startup.
2. **Grant "All files access"** - *Server* tab -> *Storage access* -> *Grant all
   files access* -> *Allow management of all files*. The servers are separate
   processes that open real paths, so Android's document picker is no use to them.
   The card clears itself as soon as you come back.
3. **Grant notifications** - the first *Start server* asks for `POST_NOTIFICATIONS`.
   Without it the foreground notification is hidden; the servers still run.
4. **Allow "install unknown apps" for Lunar Tear** - *Settings -> Apps -> Lunar Tear
   -> Install unknown apps*. Only needed for step 8, and Android's own dialog sends
   you here anyway: the app declares `REQUEST_INSTALL_PACKAGES` but cannot grant it
   to itself, so the first tap on *Install* lands on a blocked screen.
5. **Point it at the asset tree** - *Server* tab -> *Choose folder* -> the folder
   that contains `assets/` (see "Provisioning the asset tree" below). A raw dump
   root has `revisions/` at the top; the *Fix layout* button renames it in place.
6. **Start the server** - *Server* tab -> *Start server*. Migrations, then CDN,
   then the fake-Facebook auth server, then the game server, with a notification
   while they run. That is ~1.6 s here once the master data is cached and ~15 s the
   first time. The service holds a wake lock and a Wi-Fi lock for as long as it runs.
7. **Patch the client APK** - *Patch* tab -> *Choose APK* -> the pristine 3.7.1
   (versionCode 152) APK -> *Check* -> *Patch APK*. About 1.5-4.5 minutes depending
   on how warm the phone is, and ~800 MB of free space in app storage.
8. **Install the patched APK** - *Patch* tab -> *Install*. If the game already
   carries this app's key (this app patched it before) Android offers an in-place
   update and nothing else happens. A store-signed install cannot be updated over,
   and the game has to be uninstalled first - which the app deliberately does not do.
9. **Extend the master data to 2030** - *Patch* tab -> *Extend to 2030*, then
   *Reload server*. The patch writes the file; the reload is what makes the
   *running* game server re-read it, over its admin webhook.
10. **Play** - launch the game. It talks to `127.0.0.1:8003` (gRPC), `:8080` (CDN)
    and `:3000` (the auth server pretending to be Facebook).

Guards, all checked before anything is written:
* the manifest must be versionCode **152** / versionName **3.7.1**;
* `lib/arm64-v8a/libil2cpp.so` and
  `assets/bin/Data/Managed/Metadata/global-metadata.dat` must be present;
* every one of the 13 `libil2cpp.so` patch sites must match the pre-image
  recorded from the real 3.7.1 binary (a mismatch aborts the whole patch,
  writing nothing);
* every *required* metadata string must fit inside the string it replaces. The
  Facebook domain rewrite is best-effort: it is reported and skipped when it does
  not fit, because the DEX half carries the redirect;
* the Java Facebook SDK constants must be found in the DEX pools
  (`facebook.com`, `m.%s`, `https://graph.%s`, `com.facebook.katana`); a missing
  one aborts the patch rather than producing a client that still talks to Facebook.

**Installing the patched APK replaces the game.** Its signature is ours, so an
existing store-signed install has to be uninstalled first - the app never does that
for you, and does not need to: player state is not kept in the game's data, it is in
the host app's `files/db/game.db`, so re-installing the game loses no progress.

**The same is not true of the host app itself.** Uninstalling it, or clearing its
data, takes that database with it - every account, quest and pull. There is no
export button yet, so the way to keep a copy is:

```powershell
adb exec-out run-as dev.lunartear.host.debug cat files/db/game.db     > game.db
adb exec-out run-as dev.lunartear.host.debug cat files/db/game.db-wal > game.db-wal
```

Updating the app in place (`adb install -r`) never touches it.

Everything is installed under `tools/.cache/` — no machine-wide SDK, Gradle or Go cache
is touched. `server/gen/` in the upstream checkout is regenerated (it is gitignored
there); no tracked upstream file is modified.

## Why the fiddly bits are the way they are

* **`lib*.so` names + `useLegacyPackaging=true` + `keepDebugSymbols`** — Android only
  extracts `lib*.so` into an executable directory, and AGP would otherwise run the NDK
  `strip` tool over the Go PIE executables and corrupt them.
* **Generation goes through a staging directory** — protoc's code-generator plugins are
  spawned with piped stdio and are denied directory creation outside this repository.
* **`lt-migrate` exists** — the upstream server does *not* migrate on startup
  (`database.Open` only sets pragmas); the upstream Docker entrypoint shells out to the
  `goose` CLI, which does not exist on Android.
* **The port override patches are mandatory** — an unprivileged Android app cannot bind
  port 443, so the client must be patched to use a high port.
* **The tab row gets `safeDrawingPadding()`** - Android 15 draws targetSdk-35 apps
  edge to edge whether they ask for it or not. Without the padding the tab row sat
  inside the status bar's rectangle, where the status bar window rather than the app
  receives the touches, so on the phone the tabs could not be switched at all and the
  Patch tab was unreachable. Found by walking this flow on the device.
* **`network_security_config.xml` permits cleartext to loopback only** - the
  supervisor asks the game server to re-read the master data over
  `http://127.0.0.1:8082`. Cleartext is off by default for targetSdk 28+, so both
  *Reload* buttons used to fail with `Cleartext HTTP traffic to 127.0.0.1 not
  permitted` while the freshly patched file sat unused on disk. The exception is
  scoped to `127.0.0.1`/`localhost` rather than setting `usesCleartextTraffic` on the
  app, which would allow cleartext anywhere.

### Three packaging traps that only show up as `INSTALL_FAILED_INVALID_APK`

The client declares `extractNativeLibs="false"`, which means its native libraries are
mapped straight out of the APK. Three separate things have to be right, and none of them
is reported clearly by any tool:

1. **Native libraries must be page-aligned (4096), not just 4-byte aligned.** `zipalign
   -c -v 4` happily reports "Verification succesful" on an APK the installer rejects; only
   `zipalign -c -p -v 4` checks the page alignment that matters. The zip writer in
   `ApkZipWriter` therefore takes a per-entry alignment (4096 for `lib/**.so`, 4 for
   everything else).
2. **apksig rewrites the archive by default**, which shifts every entry and loses that
   alignment. Signing needs `.setAlignmentPreserved(true)` and
   `.setLibraryPageAlignmentBytes(4096)`.
3. **v1 (JAR) signing re-zips the APK** to insert `META-INF/MANIFEST.MF` and friends,
   destroying the alignment again. v1 is disabled: the v2 scheme covers API 24+, which is
   this client's `minSdkVersion`, and the `apksigner` CLI makes the same choice.

`ApkPatcher` now checks the invariant twice — on the unsigned archive and again on the
signed APK — so a regression here fails the patch instead of producing an APK that will
not install.

See `versions.lock.json` for the exact upstream commit and the hashes of the protos and
migrations the native binaries were built from.
