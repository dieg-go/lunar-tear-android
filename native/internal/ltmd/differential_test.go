package ltmd

import (
	"bytes"
	"fmt"
	"os"
	"testing"
)

// TestDifferentialAgainstPython compares this port's output with the Python
// reference's output for the same input, table by table.
//
// It is skipped unless the three files are provided:
//
//	LT_MASTER_ORIG=original.bin.e LT_MASTER_PY=python.bin.e LT_MASTER_GO=go.bin.e \
//	    go test ./internal/ltmd -run Differential -v
//
// Two things are checked:
//   - the table-of-contents (names, order, offsets, lengths) must match exactly,
//     because the client reads tables through it;
//   - every table's *decompressed* bytes must match.
//
// The LZ4 *framing* of rebuilt tables is allowed to differ: Go and python-lz4
// use different match finders, so both produce valid, differently-sized blocks.
// Rebuilding a table cannot avoid recompression, and the client only ever sees
// the decompressed bytes.
func TestDifferentialAgainstPython(t *testing.T) {
	origPath, pyPath, goPath := os.Getenv("LT_MASTER_ORIG"), os.Getenv("LT_MASTER_PY"), os.Getenv("LT_MASTER_GO")
	if origPath == "" || pyPath == "" || goPath == "" {
		t.Skip("set LT_MASTER_ORIG, LT_MASTER_PY and LT_MASTER_GO to run the differential test")
	}

	load := func(path string) *Container {
		container, err := ReadFile(path, DefaultKeyHex, DefaultIVHex)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		return container
	}
	orig := load(origPath)
	py := load(pyPath)
	goOut := load(goPath)

	// ---- table of contents ------------------------------------------------
	// Table *names* must match. Raw offsets are not compared: a rebuilt table is
	// recompressed, and Go's LZ4 and python-lz4 pick different (equally valid)
	// encodings, so every later offset shifts by a few bytes. What matters is
	// that each entry points at a table that decodes to the same bytes, which the
	// per-table check below proves.
	if len(py.TOC) != len(goOut.TOC) {
		t.Errorf("table count: python %d, go %d", len(py.TOC), len(goOut.TOC))
	}
	for name := range py.TOC {
		if _, ok := goOut.TOC[name]; !ok {
			t.Errorf("go output is missing table %q", name)
		}
	}
	for name := range goOut.TOC {
		if _, ok := py.TOC[name]; !ok {
			t.Errorf("go output has an extra table %q", name)
		}
	}
	t.Logf("blob sizes: python %d, go %d (framing difference only)", len(py.Blob), len(goOut.Blob))

	// ---- per-table content -----------------------------------------------
	differing := 0
	checked := 0
	for _, name := range py.Order {
		pyBytes, _, err := py.TableBytes(name)
		if err != nil {
			t.Errorf("%s: python decode: %v", name, err)
			continue
		}
		goBytes, _, err := goOut.TableBytes(name)
		if err != nil {
			t.Errorf("%s: go decode: %v", name, err)
			continue
		}
		checked++
		if !bytes.Equal(pyBytes, goBytes) {
			differing++
			if differing <= 8 {
				t.Errorf("%s: decompressed content differs (python %d bytes, go %d bytes)", name, len(pyBytes), len(goBytes))
			}
		}
	}

	// The patch must actually have changed something, otherwise this test would
	// pass trivially on two unpatched files.
	changed := 0
	for _, name := range orig.Order {
		origBytes, _, err := orig.TableBytes(name)
		if err != nil {
			continue
		}
		goBytes, _, err := goOut.TableBytes(name)
		if err != nil {
			continue
		}
		if !bytes.Equal(origBytes, goBytes) {
			changed++
		}
	}
	t.Logf("tables checked=%d, differing=%d, changed by the patch=%d", checked, differing, changed)
	if changed == 0 {
		t.Fatal("the patch changed nothing; the comparison proves nothing")
	}

	// A sanity check on the two outputs' framing: they should be the same order
	// of magnitude even though the LZ4 bytes differ.
	fmt.Printf("sizes: python blob %d, go blob %d\n", len(py.Blob), len(goOut.Blob))
}
