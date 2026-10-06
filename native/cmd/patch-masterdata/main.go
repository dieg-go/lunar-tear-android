// Command patch-masterdata extends a NieR Re[in]carnation master-data file to
// 2030 - the Android-side port of lunar-scripts/patch_masterdata.py.
//
// The container is AES-128-CBC over a msgpack table-of-contents header plus a
// data blob of LZ4-packed tables. Values are rewritten in place at the byte
// level (see internal/ltmd) because re-serialising a row would re-encode int64
// columns and the client's schema validator rejects the result.
package main

import (
	"flag"
	"fmt"
	"os"
	"strings"

	"lunar-tear-android/native/internal/ltmd"
)

func main() {
	input := flag.String("input", "assets/release/20240404193219.bin.e", "input .bin.e file")
	output := flag.String("output", "", "output .bin.e file (default: overwrite the input)")
	keyHex := flag.String("key", ltmd.DefaultKeyHex, "AES key as hex")
	ivHex := flag.String("iv", ltmd.DefaultIVHex, "AES IV as hex")
	keyFile := flag.String("key-file", "", "raw key file (16 or 32 bytes); overrides -key")
	ivFile := flag.String("iv-file", "", "raw IV file (16 bytes); overrides -iv")
	dryRun := flag.Bool("dry-run", false, "decrypt and patch, report, but write nothing")
	quiet := flag.Bool("quiet", false, "only print the final summary line")
	flag.Parse()

	if *keyFile != "" {
		raw, err := os.ReadFile(*keyFile)
		if err != nil {
			fatalf("read key file: %v", err)
		}
		*keyHex = fmt.Sprintf("%x", raw)
	}
	if *ivFile != "" {
		raw, err := os.ReadFile(*ivFile)
		if err != nil {
			fatalf("read iv file: %v", err)
		}
		*ivHex = fmt.Sprintf("%x", raw)
	}
	if *output == "" {
		*output = *input
	}

	key, iv, err := ltmd.KeyIV(*keyHex, *ivHex)
	if err != nil {
		fatalf("%v", err)
	}

	if !*quiet {
		fmt.Printf("Reading %s...\n", *input)
	}
	container, err := ltmd.ReadFile(*input, *keyHex, *ivHex)
	if err != nil {
		fatalf("read: %v", err)
	}
	if !*quiet {
		fmt.Printf("  %d tables, data blob %d bytes\n", len(container.TOC), len(container.Blob))
	}

	result, err := ltmd.Patch(container, *dryRun)
	if err != nil {
		fatalf("patch: %v", err)
	}

	if !*quiet {
		fmt.Printf("\nPatched %d values across %d tables:\n", result.TotalPatched, len(result.Stats))
		for _, stat := range result.Stats {
			suffix := ""
			if stat.Skipped > 0 {
				suffix = fmt.Sprintf(" (skipped %d rows by filter)", stat.Skipped)
			}
			fmt.Printf("  %s: %d values%s\n", stat.Table, stat.Patched, suffix)
		}
		if len(result.Emptied) > 0 {
			fmt.Printf("\nEmptied tables: %s\n", strings.Join(result.Emptied, ", "))
		}
		if len(result.Skipped) > 0 {
			fmt.Printf("Skipped tables: %s\n", strings.Join(result.Skipped, ", "))
		}
		if len(result.AddedRows) > 0 {
			fmt.Printf("Added rows: %s\n", strings.Join(result.AddedRows, ", "))
		}
		fmt.Printf("\nGimmick sequence schedules: %d duplicate rows expired\n", result.GimmickZeroed)
		for _, camp := range result.Campaigns {
			fmt.Printf("%s: dedup-extended %d rows\n", camp.Table, camp.Patched)
		}
		fmt.Printf("Labyrinth seasons: %d rows windowed\n", result.LabyrinthRows)
		fmt.Printf("Wolf chapter (314) BattlePointIndex: %d rows corrected 8->5\n", result.WolfFixed)
	}

	if *dryRun {
		if !*quiet {
			fmt.Println("\n[DRY RUN] Skipping rebuild and encryption.")
		}
		return
	}

	plain := container.Plaintext()
	encrypted, err := ltmd.Encrypt(plain, key, iv)
	if err != nil {
		fatalf("encrypt: %v", err)
	}
	if err := os.WriteFile(*output, encrypted, 0o644); err != nil {
		fatalf("write: %v", err)
	}

	if *quiet {
		fmt.Printf("OK %s %d bytes\n", *output, len(encrypted))
		return
	}
	fmt.Printf("\nHeader: %d bytes, blob: %d bytes\n", result.HeaderBytes, result.BlobBytes)
	fmt.Printf("Re-encrypted size: %d bytes\n", len(encrypted))
	fmt.Printf("Done! Patched binary written to %s\n", *output)
}

func fatalf(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "patch-masterdata: "+format+"\n", args...)
	os.Exit(1)
}
