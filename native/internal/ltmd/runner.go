package ltmd

import (
	"fmt"
	"sort"
	"time"
)

// timeNow is indirected so tests can pin "now" and get stable campaign results.
var timeNow = time.Now

// TableStat is the per-table outcome, kept for the report and for tests.
type TableStat struct {
	Table   string
	Patched int
	Skipped int
	Note    string
}

// Result summarises a patch run.
type Result struct {
	Stats        []TableStat
	TotalPatched int
	Emptied      []string
	Skipped      []string
	AddedRows    []string
	GimmickZeroed int
	LabyrinthRows int
	WolfFixed     int
	Campaigns     []TableStat
	HeaderBytes   int
	BlobBytes     int
	OriginalBytes int
}

// Patch applies the whole rule set to a decrypted container, in the same order
// as the reference implementation. Order matters: the blanket column bump runs
// first and later passes refine individual tables that it already touched.
//
// dryRun stops before the rebuild/encrypt step but still performs every mutation
// so the counts match a real run.
func Patch(container *Container, dryRun bool) (*Result, error) {
	nowMs := nowMillis()
	targetEnd := TargetEndMs()
	maxPatch := MaxPatchMs()

	newBlobs := map[string][]byte{}
	result := &Result{}

	// 1. Blanket EndDatetime bump ------------------------------------------
	for _, entry := range patchColumns {
		if skipTables[entry.Table] {
			continue
		}
		var rowFilter *[2]int64
		if entry.Table == "m_gimmick_sequence_schedule" {
			rowFilter = &[2]int64{1, SchedulePatchCutoffMs()}
		}

		blob, compressed, err := container.TableBytes(entry.Table)
		if err != nil {
			// Table absent from this build: the reference warns and moves on.
			continue
		}
		patched, skipped, err := PatchTableBlob(blob, entry.Cols, rowFilter, targetEnd)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", entry.Table, err)
		}
		if patched == 0 {
			// Leave the original bytes untouched so unmodified tables stay
			// byte-identical in the output.
			continue
		}
		encoded, err := EncodeTable(blob, compressed)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", entry.Table, err)
		}
		newBlobs[entry.Table] = encoded
		result.Stats = append(result.Stats, TableStat{Table: entry.Table, Patched: patched, Skipped: skipped})
		result.TotalPatched += patched
	}

	// 2. Tables that must be empty -----------------------------------------
	for _, name := range emptyTables {
		if _, ok := container.TOC[name]; !ok {
			continue
		}
		newBlobs[name] = AppendArrayHeader(nil, 0) // msgpack.packb([])
		result.Emptied = append(result.Emptied, name)
	}

	// 3. Added rows --------------------------------------------------------
	for _, addition := range tableRowAdditions {
		blob, compressed, err := tableBytesPreferringUpdated(container, newBlobs, addition.Table)
		if err != nil {
			continue
		}
		updated, changed, err := AddTableRows(blob, addition.Rows, 0)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", addition.Table, err)
		}
		if !changed {
			continue
		}
		encoded, err := EncodeTable(updated, compressed)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", addition.Table, err)
		}
		newBlobs[addition.Table] = encoded
		result.AddedRows = append(result.AddedRows, fmt.Sprintf("%s (+%d row)", addition.Table, len(addition.Rows)))
	}

	// 4. Gimmick schedule dedup --------------------------------------------
	if zeroed, err := applyTable(container, newBlobs, "m_gimmick_sequence_schedule",
		func(blob []byte) (int, error) { return PatchGimmickSequenceSchedules(blob) }); err != nil {
		return nil, err
	} else {
		result.GimmickZeroed = zeroed
	}

	// 5. Campaign dedup ----------------------------------------------------
	for _, cfg := range campaignCfgs {
		campTable := "m_" + cfg.Family + "_campaign"
		if _, ok := container.TOC[campTable]; !ok {
			continue
		}
		if _, ok := container.TOC[cfg.TargetTable]; !ok {
			continue
		}
		targetRows, err := decodeTableRows(container, cfg.TargetTable)
		if err != nil {
			return nil, err
		}
		var effectRows []interface{}
		if cfg.EffectTable != "" {
			if effectRows, err = decodeTableRows(container, cfg.EffectTable); err != nil {
				return nil, err
			}
		}
		bumped, err := applyTable(container, newBlobs, campTable, func(blob []byte) (int, error) {
			return PatchCampaignDedup(blob, targetRows, effectRows, cfg, nowMs, targetEnd, maxPatch)
		})
		if err != nil {
			return nil, err
		}
		result.Campaigns = append(result.Campaigns, TableStat{Table: campTable, Patched: bumped})
	}

	// 6. Labyrinth seasons -------------------------------------------------
	if written, err := applyTable(container, newBlobs, "m_event_quest_labyrinth_season",
		func(blob []byte) (int, error) { return PatchLabyrinthSeasons(blob, targetEnd) }); err != nil {
		return nil, err
	} else {
		result.LabyrinthRows = written
	}

	// 7. Wolf chapter battle point -----------------------------------------
	if fixed, err := applyTable(container, newBlobs, "m_battle_group",
		func(blob []byte) (int, error) { return PatchWolfChapterBattlePoint(blob) }); err != nil {
		return nil, err
	} else {
		result.WolfFixed = fixed
	}

	result.Skipped = sortedKeys(skipTables)
	if dryRun {
		return result, nil
	}

	// 8. Rebuild the container, preserving the original table order --------
	order := make([]string, 0, len(container.TOC))
	for name := range container.TOC {
		order = append(order, name)
	}
	sort.Slice(order, func(i, j int) bool {
		return container.TOC[order[i]][0] < container.TOC[order[j]][0]
	})

	blobParts := make([][]byte, 0, len(order))
	newTOC := make(map[string][2]int, len(order))
	offset := 0
	for _, name := range order {
		var part []byte
		if updated, ok := newBlobs[name]; ok {
			part = updated
		} else {
			entry := container.TOC[name]
			part = container.Blob[entry[0] : entry[0]+entry[1]]
		}
		newTOC[name] = [2]int{offset, len(part)}
		blobParts = append(blobParts, part)
		offset += len(part)
	}

	header := BuildHeader(order, func(name string) [2]int { return newTOC[name] })
	blob := joinBytes(blobParts)
	container.Blob = blob
	container.TOC = newTOC
	container.Order = order
	container.NewHeader = header
	result.HeaderBytes = len(header)
	result.BlobBytes = len(blob)
	return result, nil
}

// nowMillis is the reference's `int(datetime.now(tz=utc).timestamp() * 1000)`,
// used to decide whether a campaign row is currently running.
func nowMillis() int64 {
	return timeNow().UnixMilli()
}

// applyTable decodes a table, runs a mutator that edits it in place, and stores
// the re-encoded result.
//
// It prefers an already-updated blob over the original one, exactly like the
// reference's apply_to_table(): several passes touch the same table (the blanket
// datetime bump runs before the gimmick-schedule dedup), and the later pass must
// build on the earlier one's output rather than re-deriving from the original.
func applyTable(container *Container, newBlobs map[string][]byte, name string, mutate func([]byte) (int, error)) (int, error) {
	var blob []byte
	var compressed bool
	if updated, ok := newBlobs[name]; ok {
		decoded, comp, err := DecodeTable(updated)
		if err != nil {
			return 0, fmt.Errorf("%s: %w", name, err)
		}
		blob, compressed = decoded, comp
	} else {
		decoded, comp, err := container.TableBytes(name)
		if err != nil {
			return 0, nil // absent table: the reference warns and continues
		}
		blob, compressed = decoded, comp
	}

	count, err := mutate(blob)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", name, err)
	}
	encoded, err := EncodeTable(blob, compressed)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", name, err)
	}
	newBlobs[name] = encoded
	return count, nil
}

// tableBytesPreferringUpdated mirrors apply_to_table's source selection.
func tableBytesPreferringUpdated(container *Container, newBlobs map[string][]byte, name string) ([]byte, bool, error) {
	if updated, ok := newBlobs[name]; ok {
		return DecodeTable(updated)
	}
	return container.TableBytes(name)
}

func decodeTableRows(container *Container, name string) ([]interface{}, error) {
	blob, _, err := container.TableBytes(name)
	if err != nil {
		return nil, err
	}
	return decodeRows(blob)
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func joinBytes(parts [][]byte) []byte {
	total := 0
	for _, part := range parts {
		total += len(part)
	}
	out := make([]byte, 0, total)
	for _, part := range parts {
		out = append(out, part...)
	}
	return out
}
