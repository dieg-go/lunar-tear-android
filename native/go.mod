// Module for the Android-side helper tools that ship inside the APK alongside
// the upstream lunar-tear server binaries.
//
// Nothing here imports lunar-tear/server/internal/... : Go forbids importing
// another module's internal packages, so the master-data codec is implemented
// independently in internal/ltmd (verified against the upstream reader and
// against the Python reference by tools/differential.ps1).
module lunar-tear-android/native

go 1.25.8

require (
	github.com/pierrec/lz4/v4 v4.1.26
	github.com/pressly/goose/v3 v3.27.1
	github.com/vmihailenco/msgpack/v5 v5.4.1
	modernc.org/sqlite v1.49.1
)

require (
	github.com/dustin/go-humanize v1.0.1 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/mattn/go-isatty v0.0.21 // indirect
	github.com/mfridman/interpolate v0.0.2 // indirect
	github.com/ncruces/go-strftime v1.0.0 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20230129092748-24d4a6f8daec // indirect
	github.com/sethvargo/go-retry v0.3.0 // indirect
	github.com/vmihailenco/tagparser/v2 v2.0.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/sync v0.20.0 // indirect
	golang.org/x/sys v0.43.0 // indirect
	modernc.org/libc v1.72.1 // indirect
	modernc.org/mathutil v1.7.1 // indirect
	modernc.org/memory v1.11.0 // indirect
)
