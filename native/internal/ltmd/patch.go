package ltmd

import (
	"bytes"
	"fmt"
	"sort"
	"time"

	"github.com/vmihailenco/msgpack/v5"
)

// Time windows the client is happy with, and the far-future value everything is
// extended to. These mirror the reference exactly; the values are computed from
// the same dates rather than hardcoded, so they cannot drift.
var (
	TargetEnd = time.Date(2030, 12, 31, 23, 59, 59, 0, time.UTC)
	MinPatch  = time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)
	MaxPatch  = time.Date(2030, 1, 1, 0, 0, 0, 0, time.UTC)
	// Past this point there are already 1022 gimmick schedules, and the client
	// caps MaxGimmickSequenceSchedule at 1024, so later ones would be dropped.
	SchedulePatchCutoff = time.Date(2023, 2, 1, 0, 0, 0, 0, time.UTC)
)

// PATCH_COLUMNS from the reference: table -> int64 datetime column indices.
// These indices come from the entity definitions in schemas.json. The order is
// the reference's dict order, kept so reports are diffable.
var patchColumns = []struct {
	Table string
	Cols  []int
}{
	{"m_appeal_dialog", []int{5}},
	{"m_big_hunt_schedule", []int{3}},
	{"m_cage_ornament", []int{2}},
	{"m_consumable_item_term", []int{2}},
	{"m_costume_collection_bonus", []int{6}},
	{"m_dokan", []int{4}},
	{"m_event_quest_chapter", []int{9}},
	{"m_event_quest_daily_group", []int{2}},
	{"m_event_quest_guerrilla_free_open", []int{4}},
	{"m_event_quest_limit_content", []int{6}},
	{"m_event_quest_limit_content_deck_restriction", []int{4}},
	{"m_gacha_medal", []int{4}},
	{"m_gimmick_sequence_schedule", []int{2}},
	{"m_important_item_effect", []int{6}},
	{"m_login_bonus", []int{5, 6}},
	{"m_mission_pass", []int{2}},
	{"m_mission_term", []int{2}},
	{"m_mom_banner", []int{7}},
	{"m_mom_point_banner", []int{4}},
	{"m_navi_cut_in", []int{4}},
	{"m_omikuji", []int{2}},
	{"m_portal_cage_access_point_function_group_schedule", []int{5}},
	{"m_possession_acquisition_route", []int{7}},
	{"m_premium_item", []int{3}},
	{"m_pvp_season", []int{3}},
	{"m_quest_bonus_term_group", []int{3}},
	{"m_quest_schedule", []int{3}},
	{"m_shop", []int{10}},
	{"m_shop_item_cell_term", []int{2}},
	{"m_tip", []int{6}},
	{"m_title_flow_movie", []int{3}},
	{"m_webview_mission", []int{5}},
	{"m_webview_panel_mission", []int{4}},
}

// m_omikuji is deliberately never extended (its schedule is driven by the real
// world clock and extending it breaks the shrine).
var skipTables = map[string]bool{"m_omikuji": true}

// Tables that must be empty to suppress their feature entirely.
var emptyTables = []string{"m_maintenance"}

// Unhides the Labyrinth (EventQuestType=12) in the side-quest menu: the client
// hides every chapter of an EventQuestType missing from this table.
var tableRowAdditions = []struct {
	Table string
	Rows  [][]int64
}{
	{"m_event_quest_unlock_condition", [][]int64{{12, 0, 0, 1, 21, 0}}},
}

// Campaign families handled by the dedup helper rather than the blanket bump.
type CampaignCfg struct {
	Family        string
	TargetTable   string
	EffectTable   string
	IDCol         int
	TGCol         int
	EffTypeCol    int
	EffValCol     int
	EffGroupCol   int
	StartCol      int
	EndCol        int
	UserStatusCol int
	// Entity-specific targets are dropped so rerun boosts are not extended
	// forever for one character/costume/weapon.
	EntityIDTargets map[int64]bool
}

var enhanceEntityTargets = map[int64]bool{11: true, 12: true, 13: true, 21: true, 22: true, 23: true, 31: true, 32: true}

// MAIN_QUEST_QUEST_ID, SUB_QUEST_QUEST_ID
var questEntityTargets = map[int64]bool{5: true, 7: true}

var campaignCfgs = []CampaignCfg{
	{
		Family: "enhance", TargetTable: "m_enhance_campaign_target_group",
		IDCol: 0, TGCol: 1, EffTypeCol: 2, EffValCol: 3, StartCol: 4, EndCol: 5, UserStatusCol: 6,
		EntityIDTargets: enhanceEntityTargets,
	},
	{
		Family: "quest", TargetTable: "m_quest_campaign_target_group",
		EffectTable: "m_quest_campaign_effect_group",
		IDCol: 0, TGCol: 1, EffGroupCol: 2, StartCol: 3, EndCol: 4, UserStatusCol: 5,
		EntityIDTargets: questEntityTargets,
	},
}

// ------------------------------------------------------------------ mutators

// PatchTableBlob bumps int64 datetime columns in colIndices to targetEndMs.
// Returns (patched, skipped) exactly like the reference.
func PatchTableBlob(blob []byte, colIndices []int, rowFilter *[2]int64, targetEndMs int64) (int, int, error) {
	inCols := make(map[int]bool, len(colIndices))
	for _, c := range colIndices {
		inCols[c] = true
	}

	rowCount, pos, err := ReadArrayLen(blob, 0)
	if err != nil {
		return 0, 0, err
	}
	patched, skipped := 0, 0
	for row := 0; row < rowCount; row++ {
		colCount, p, err := ReadArrayLen(blob, pos)
		if err != nil {
			return patched, skipped, err
		}

		skipRow := false
		if rowFilter != nil {
			filterCol, filterMax := int(rowFilter[0]), rowFilter[1]
			fp := p
			limit := filterCol + 1
			if colCount < limit {
				limit = colCount
			}
			for ci := 0; ci < limit; ci++ {
				if ci == filterCol {
					if blob[fp] == 0xd3 {
						val, err := ReadInt64At(blob, fp)
						if err != nil {
							return patched, skipped, err
						}
						if val >= filterMax {
							skipRow = true
						}
					}
					break
				}
				if fp, err = SkipValue(blob, fp); err != nil {
					return patched, skipped, err
				}
			}
		}

		if skipRow {
			skipped++
			for ci := 0; ci < colCount; ci++ {
				if p, err = SkipValue(blob, p); err != nil {
					return patched, skipped, err
				}
			}
		} else {
			for ci := 0; ci < colCount; ci++ {
				if inCols[ci] && blob[p] == 0xd3 {
					val, err := ReadInt64At(blob, p)
					if err != nil {
						return patched, skipped, err
					}
					if val >= MinPatchMs() && val <= MaxPatchMs() {
						if err := WriteInt64At(blob, p, targetEndMs); err != nil {
							return patched, skipped, err
						}
						patched++
					}
				}
				if p, err = SkipValue(blob, p); err != nil {
					return patched, skipped, err
				}
			}
		}
		pos = p
	}
	return patched, skipped, nil
}

// AddTableRows appends rows whose key is not already present. Idempotent.
// Returns (newBlob, changed).
func AddTableRows(blob []byte, rows [][]int64, keyCol int) ([]byte, bool, error) {
	count, pos, err := ReadArrayLen(blob, 0)
	if err != nil {
		return nil, false, err
	}
	existing := make(map[int64]bool, count)
	p := pos
	for i := 0; i < count; i++ {
		_, rowPos, err := ReadArrayLen(blob, p)
		if err != nil {
			return nil, false, err
		}
		key, err := ReadInt(blob, rowPos)
		if err != nil {
			return nil, false, err
		}
		existing[key] = true
		if p, err = SkipValue(blob, p); err != nil {
			return nil, false, err
		}
	}

	toAdd := make([][]int64, 0, len(rows))
	for _, row := range rows {
		if !existing[row[keyCol]] {
			toAdd = append(toAdd, row)
		}
	}
	if len(toAdd) == 0 {
		return nil, false, nil
	}

	total := count + len(toAdd)
	out := AppendArrayHeader(nil, total)
	out = append(out, blob[pos:]...)
	for _, row := range toAdd {
		out = AppendArrayHeader(out, len(row))
		for _, value := range row {
			out = AppendInt(out, value)
		}
	}
	return out, true, nil
}

// PatchLabyrinthSeasons leaves exactly one within-period season per chapter;
// extras get EndDatetime = 0. The client's
// TryGetEventQuestLabyrinthWithinPeriod only returns true if exactly one row
// passes IsWithinThePeriod.
func PatchLabyrinthSeasons(blob []byte, targetEndMs int64) (int, error) {
	type row struct {
		chapter, season  int64
		startPos, endPos int
	}
	var rows []row

	rowCount, pos, err := ReadArrayLen(blob, 0)
	if err != nil {
		return 0, err
	}
	for i := 0; i < rowCount; i++ {
		colCount, p, err := ReadArrayLen(blob, pos)
		if err != nil {
			return 0, err
		}
		cp := p
		positions := make([]int, 0, colCount)
		for c := 0; c < colCount; c++ {
			positions = append(positions, cp)
			if cp, err = SkipValue(blob, cp); err != nil {
				return 0, err
			}
		}
		chapter, err := ReadInt(blob, positions[0])
		if err != nil {
			return 0, err
		}
		season, err := ReadInt(blob, positions[1])
		if err != nil {
			return 0, err
		}
		rows = append(rows, row{chapter, season, positions[2], positions[3]})
		pos = cp
	}

	maxSeason := map[int64]int64{}
	for _, r := range rows {
		if r.season > maxSeason[r.chapter] {
			maxSeason[r.chapter] = r.season
		}
	}

	written := 0
	for _, r := range rows {
		if blob[r.startPos] != 0xd3 || blob[r.endPos] != 0xd3 {
			continue
		}
		if r.season == maxSeason[r.chapter] {
			if err := WriteInt64At(blob, r.startPos, MinPatchMs()); err != nil {
				return written, err
			}
			if err := WriteInt64At(blob, r.endPos, targetEndMs); err != nil {
				return written, err
			}
		} else if err := WriteInt64At(blob, r.endPos, 0); err != nil {
			return written, err
		}
		written++
	}
	return written, nil
}

// PatchGimmickSequenceSchedules keeps one active schedule per
// FirstGimmickSequenceId; duplicates get EndDt = 0, otherwise they render as
// overlapping ornaments and cancel each other. Lowest (StartDt, ScheduleId) wins,
// matching the server's own dedup.
func PatchGimmickSequenceSchedules(blob []byte) (int, error) {
	type row struct {
		firstSeq, startDt, schedID int64
		endPos                     int
	}
	var rows []row

	rowCount, pos, err := ReadArrayLen(blob, 0)
	if err != nil {
		return 0, err
	}
	for i := 0; i < rowCount; i++ {
		colCount, p, err := ReadArrayLen(blob, pos)
		if err != nil {
			return 0, err
		}
		cp := p
		positions := make([]int, 0, colCount)
		for c := 0; c < colCount; c++ {
			positions = append(positions, cp)
			if cp, err = SkipValue(blob, cp); err != nil {
				return 0, err
			}
		}
		schedID, err := ReadInt(blob, positions[0])
		if err != nil {
			return 0, err
		}
		var startDt int64
		if blob[positions[1]] == 0xd3 {
			if startDt, err = ReadInt64At(blob, positions[1]); err != nil {
				return 0, err
			}
		}
		firstSeq, err := ReadInt(blob, positions[3])
		if err != nil {
			return 0, err
		}
		rows = append(rows, row{firstSeq, startDt, schedID, positions[2]})
		pos = cp
	}

	canonical := map[int64][2]int64{}
	seen := map[int64]bool{}
	for _, r := range rows {
		if !seen[r.firstSeq] || (r.startDt < canonical[r.firstSeq][0]) ||
			(r.startDt == canonical[r.firstSeq][0] && r.schedID < canonical[r.firstSeq][1]) {
			canonical[r.firstSeq] = [2]int64{r.startDt, r.schedID}
			seen[r.firstSeq] = true
		}
	}

	zeroed := 0
	for _, r := range rows {
		if canonical[r.firstSeq] == [2]int64{r.startDt, r.schedID} {
			continue
		}
		if blob[r.endPos] != 0xd3 {
			continue
		}
		if err := WriteInt64At(blob, r.endPos, 0); err != nil {
			return zeroed, err
		}
		zeroed++
	}
	return zeroed, nil
}

// PatchWolfChapterBattlePoint fixes BattlePointIndex 8 -> 5 for battle groups
// 1880..1889: the locale asset has BattlePoints 1-5 only, and the client NREs
// during battle setup otherwise. Idempotent (only rewrites while it is 8).
func PatchWolfChapterBattlePoint(blob []byte) (int, error) {
	rowCount, pos, err := ReadArrayLen(blob, 0)
	if err != nil {
		return 0, err
	}
	patched := 0
	for i := 0; i < rowCount; i++ {
		colCount, p, err := ReadArrayLen(blob, pos)
		if err != nil {
			return patched, err
		}
		cp := p
		positions := make([]int, 0, colCount)
		for c := 0; c < colCount; c++ {
			positions = append(positions, cp)
			if cp, err = SkipValue(blob, cp); err != nil {
				return patched, err
			}
		}
		bgID, err := ReadInt(blob, positions[0])
		if err != nil {
			return patched, err
		}
		if bgID >= 1880 && bgID <= 1889 && colCount > 6 && blob[positions[6]] == 0x08 {
			blob[positions[6]] = 0x05
			patched++
		}
		pos = cp
	}
	return patched, nil
}

// ------------------------------------------------------------------ campaigns

type targetPair struct{ typ, val int64 }

type campKey struct {
	effType  int64
	targets  string // canonical form of the sorted target pairs
	extra    int64
	hasExtra bool
}

func makeCampKey(effType int64, targets []targetPair, extra int64, hasExtra bool) campKey {
	key := campKey{effType: effType, extra: extra, hasExtra: hasExtra}
	var buf bytes.Buffer
	for _, t := range targets {
		fmt.Fprintf(&buf, "%d:%d;", t.typ, t.val)
	}
	key.targets = buf.String()
	return key
}

// Subsumes reports whether every entity/quest matching narrow also matches broad.
// Port of _subsumes().
func Subsumes(broad, narrow []targetPair, family string) bool {
	broadTypes := map[int64]bool{}
	for _, t := range broad {
		broadTypes[t.typ] = true
	}
	switch family {
	case "quest":
		if broadTypes[1] { // WHOLE_QUEST subsumes everything
			return true
		}
		broadQts := map[int64]bool{}
		for _, t := range broad {
			if t.typ == 2 {
				broadQts[t.val] = true
			}
		}
		if len(broadQts) == 0 {
			return false
		}
		for _, nt := range narrow {
			switch {
			case nt.typ == 1:
				return false
			case nt.typ == 2:
				if !broadQts[nt.val] {
					return false
				}
			case nt.typ == 3 || nt.typ == 6 || nt.typ == 7:
				if !broadQts[2] {
					return false
				}
			case nt.typ == 4 || nt.typ == 5:
				if !broadQts[1] {
					return false
				}
			default:
				return false
			}
		}
		return true
	case "enhance":
		allMap := map[int64][]int64{1: {11, 12, 13}, 2: {21, 22, 23}, 3: {31, 32}}
		for _, nt := range narrow {
			covered := false
			for at, children := range allMap {
				if !broadTypes[at] {
					continue
				}
				for _, child := range children {
					if child == nt.typ {
						covered = true
						break
					}
				}
			}
			if !covered && !broadTypes[nt.typ] {
				return false
			}
		}
		return true
	}
	return false
}

// PatchCampaignDedup dedup-extends a campaign table: one baseline-value rerun per
// (effect, target) tuple, entity-specific rows dropped, then picks subsumed by a
// broader pick with the same effect+value+payload dropped.
func PatchCampaignDedup(campBlob []byte, targetRows, effectRows []interface{}, cfg CampaignCfg, nowMs, targetEndMs, maxPatchMs int64) (int, error) {
	rows, err := decodeRows(campBlob)
	if err != nil {
		return 0, err
	}

	targetsByGroup := map[int64][]targetPair{}
	for _, raw := range targetRows {
		row, err := rowInts(raw)
		if err != nil || len(row) < 4 {
			continue
		}
		targetsByGroup[row[0]] = append(targetsByGroup[row[0]], targetPair{row[2], row[3]})
	}

	var effectsByID map[int64][3]int64
	if effectRows != nil {
		effectsByID = map[int64][3]int64{}
		for _, raw := range effectRows {
			row, err := rowInts(raw)
			if err != nil || len(row) < 4 {
				continue
			}
			effectsByID[row[0]] = [3]int64{row[1], row[2], row[3]}
		}
	}

	type candidate struct {
		effVal  int64
		campID  int64
		idx     int
		targets []targetPair
	}
	groups := map[campKey][]candidate{}
	permanent := map[campKey]struct {
		effVal  int64
		targets []targetPair
	}{}

	for idx, raw := range rows {
		row, err := rowInts(raw)
		if err != nil {
			return 0, fmt.Errorf("campaign row %d: %w", idx, err)
		}
		get := func(col int) int64 {
			if col < len(row) {
				return row[col]
			}
			return 0
		}
		if get(cfg.UserStatusCol) != 1 {
			continue
		}
		targets := targetsByGroup[get(cfg.TGCol)]
		if len(targets) == 0 {
			continue
		}
		entitySpecific := false
		for _, t := range targets {
			if cfg.EntityIDTargets[t.typ] {
				entitySpecific = true
				break
			}
		}
		if entitySpecific {
			continue
		}

		var effType, effVal, extra int64
		hasExtra := false
		if effectsByID != nil {
			eff, ok := effectsByID[get(cfg.EffGroupCol)]
			if !ok {
				continue
			}
			effType, effVal, extra, hasExtra = eff[0], eff[1], eff[2], true
		} else {
			effType = get(cfg.EffTypeCol)
			effVal = get(cfg.EffValCol)
		}

		sorted := append([]targetPair(nil), targets...)
		sort.Slice(sorted, func(i, j int) bool {
			if sorted[i].typ != sorted[j].typ {
				return sorted[i].typ < sorted[j].typ
			}
			return sorted[i].val < sorted[j].val
		})
		key := makeCampKey(effType, sorted, extra, hasExtra)

		startDt := get(cfg.StartCol)
		endDt := get(cfg.EndCol)
		if startDt <= nowMs && endDt >= maxPatchMs {
			permanent[key] = struct {
				effVal  int64
				targets []targetPair
			}{effVal, sorted}
		} else if endDt < maxPatchMs {
			groups[key] = append(groups[key], candidate{effVal, get(cfg.IDCol), idx, sorted})
		}
	}

	// Pass A: smallest-value rerun per key, skipping keys a permanent row covers.
	type pick struct {
		val     int64
		idx     int
		targets []targetPair
	}
	baseline := map[campKey]pick{}
	for key, candidates := range groups {
		if _, ok := permanent[key]; ok {
			continue
		}
		sort.Slice(candidates, func(i, j int) bool {
			if candidates[i].effVal != candidates[j].effVal {
				return candidates[i].effVal < candidates[j].effVal
			}
			if candidates[i].campID != candidates[j].campID {
				return candidates[i].campID < candidates[j].campID
			}
			return candidates[i].idx < candidates[j].idx
		})
		best := candidates[0]
		baseline[key] = pick{best.effVal, best.idx, best.targets}
	}

	// Pass B: drop picks subsumed by a broader pick with the same bucket.
	type effective struct {
		key     campKey
		val     int64
		targets []targetPair
	}
	var allEffective []effective
	for key, entry := range permanent {
		allEffective = append(allEffective, effective{key, entry.effVal, entry.targets})
	}
	for key, entry := range baseline {
		allEffective = append(allEffective, effective{key, entry.val, entry.targets})
	}

	picks := map[int]bool{}
	for key, entry := range baseline {
		bucketSame := func(other campKey, otherVal int64) bool {
			if other.effType != key.effType || otherVal != entry.val {
				return false
			}
			if other.hasExtra != key.hasExtra || other.extra != key.extra {
				return false
			}
			return true
		}
		subsumed := false
		for _, other := range allEffective {
			if other.key == key {
				continue
			}
			if !bucketSame(other.key, other.val) {
				continue
			}
			if !samePairs(other.targets, entry.targets) && Subsumes(other.targets, entry.targets, cfg.Family) {
				subsumed = true
				break
			}
		}
		if !subsumed {
			picks[entry.idx] = true
		}
	}

	// Now write the extended EndDatetime on the picked rows, in place.
	rowCount, pos, err := ReadArrayLen(campBlob, 0)
	if err != nil {
		return 0, err
	}
	bumped := 0
	for idx := 0; idx < rowCount; idx++ {
		colCount, p, err := ReadArrayLen(campBlob, pos)
		if err != nil {
			return bumped, err
		}
		for ci := 0; ci < colCount; ci++ {
			if ci == cfg.EndCol && picks[idx] && campBlob[p] == 0xd3 {
				if err := WriteInt64At(campBlob, p, targetEndMs); err != nil {
					return bumped, err
				}
				bumped++
			}
			if p, err = SkipValue(campBlob, p); err != nil {
				return bumped, err
			}
		}
		pos = p
	}
	return bumped, nil
}

func samePairs(a, b []targetPair) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// ------------------------------------------------------------------ decoding helpers

func decodeRows(blob []byte) ([]interface{}, error) {
	decoder := msgpack.NewDecoder(bytes.NewReader(blob))
	decoder.UseLooseInterfaceDecoding(true)
	var rows []interface{}
	if err := decoder.Decode(&rows); err != nil {
		return nil, fmt.Errorf("decode table rows: %w", err)
	}
	return rows, nil
}

func rowInts(raw interface{}) ([]int64, error) {
	list, ok := raw.([]interface{})
	if !ok {
		return nil, fmt.Errorf("row is %T, expected an array", raw)
	}
	out := make([]int64, 0, len(list))
	for _, value := range list {
		n, err := toInt64(value)
		if err != nil {
			out = append(out, 0)
			continue
		}
		out = append(out, n)
	}
	return out, nil
}

// MinPatchMs / MaxPatchMs are exposed as functions so the values are computed
// once from the dates above.
func MinPatchMs() int64 { return MinPatch.UnixMilli() }
func MaxPatchMs() int64 { return MaxPatch.UnixMilli() }
func TargetEndMs() int64 {
	return TargetEnd.UnixMilli()
}
func SchedulePatchCutoffMs() int64 { return SchedulePatchCutoff.UnixMilli() }
