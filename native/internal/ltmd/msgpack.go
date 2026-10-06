// Package ltmd implements the lunar-tear master-data container: AES-128-CBC over
// a msgpack table-of-contents header plus a data blob of LZ4-packed tables.
//
// The tables are mutated **in place** at the byte level, exactly like the
// Python reference (lunar-scripts/patch_masterdata.py). That is not a stylistic
// choice: re-serialising a row with msgpack would re-encode int64 columns using
// the tightest possible representation, and the client's schema validator
// rejects the resulting blobs. So writes go through the walker below, which
// never moves anything; it only overwrites the eight payload bytes of a 0xd3
// (int64) value that is already in the blob.
package ltmd

import "fmt"

// msgpack element tags used by the walker.
const (
	tagFixIntMax = 0x7f
	tagFixMapMin = 0x80
	tagFixArray  = 0x90
	tagFixStr    = 0xa0
	tagNil       = 0xc0
	tagInt64     = 0xd3
)

// SkipValue returns the offset just past the msgpack value at pos.
//
// Port of skip_msgpack_value() from the reference, including its tag table.
func SkipValue(data []byte, pos int) (int, error) {
	if pos >= len(data) {
		return 0, fmt.Errorf("msgpack: offset %d past end of blob (%d bytes)", pos, len(data))
	}
	tag := data[pos]

	switch {
	case tag <= tagFixIntMax || tag >= 0xe0:
		return pos + 1, nil
	case tag >= tagFixStr && tag <= 0xbf:
		return pos + 1 + int(tag&0x1f), nil
	case tag >= tagFixArray && tag <= 0x9f:
		n := int(tag & 0x0f)
		p := pos + 1
		for i := 0; i < n; i++ {
			var err error
			if p, err = SkipValue(data, p); err != nil {
				return 0, err
			}
		}
		return p, nil
	case tag >= tagFixMapMin && tag <= 0x8f:
		n := int(tag & 0x0f)
		p := pos + 1
		for i := 0; i < n*2; i++ {
			var err error
			if p, err = SkipValue(data, p); err != nil {
				return 0, err
			}
		}
		return p, nil
	}

	switch tag {
	case tagNil, 0xc2, 0xc3:
		return pos + 1, nil
	case 0xca:
		return pos + 5, nil
	case 0xcb:
		return pos + 9, nil
	case 0xcc:
		return pos + 2, nil
	case 0xcd:
		return pos + 3, nil
	case 0xce:
		return pos + 5, nil
	case 0xcf:
		return pos + 9, nil
	case 0xd0:
		return pos + 2, nil
	case 0xd1:
		return pos + 3, nil
	case 0xd2:
		return pos + 5, nil
	case 0xd3:
		return pos + 9, nil
	case 0xd4:
		return pos + 3, nil
	case 0xd5:
		return pos + 4, nil
	case 0xd6:
		return pos + 6, nil
	case 0xd7:
		return pos + 10, nil
	case 0xd8:
		return pos + 18, nil
	}

	// Length-prefixed families: bin (0xc4..0xc6), str (0xd9..0xdb), ext (0xc7..0xc9).
	var sizeBytes, extra int
	switch tag {
	case 0xc4, 0xd9, 0xc7:
		sizeBytes, extra = 1, 0
	case 0xc5, 0xda, 0xc8:
		sizeBytes, extra = 2, 0
	case 0xc6, 0xdb, 0xc9:
		sizeBytes, extra = 4, 0
	}
	if sizeBytes != 0 {
		if tag == 0xc7 || tag == 0xc8 || tag == 0xc9 {
			extra = 1 // ext has a type byte
		}
		n, err := readBigEndian(data, pos+1, sizeBytes)
		if err != nil {
			return 0, err
		}
		return pos + 1 + sizeBytes + extra + int(n), nil
	}

	// Arrays and maps with 16/32-bit lengths.
	switch tag {
	case 0xdc, 0xde:
		n, err := readBigEndian(data, pos+1, 2)
		if err != nil {
			return 0, err
		}
		items := int(n)
		if tag == 0xde {
			items *= 2
		}
		p := pos + 3
		for i := 0; i < items; i++ {
			if p, err = SkipValue(data, p); err != nil {
				return 0, err
			}
		}
		return p, nil
	case 0xdd, 0xdf:
		n, err := readBigEndian(data, pos+1, 4)
		if err != nil {
			return 0, err
		}
		items := int(n)
		if tag == 0xdf {
			items *= 2
		}
		p := pos + 5
		for i := 0; i < items; i++ {
			if p, err = SkipValue(data, p); err != nil {
				return 0, err
			}
		}
		return p, nil
	}

	return 0, fmt.Errorf("msgpack: unknown tag 0x%02x at pos %d", tag, pos)
}

// ReadArrayLen returns the number of elements in the array at pos and the offset
// of its first element.
func ReadArrayLen(data []byte, pos int) (int, int, error) {
	if pos >= len(data) {
		return 0, 0, fmt.Errorf("msgpack: offset %d past end of blob", pos)
	}
	tag := data[pos]
	switch {
	case tag >= tagFixArray && tag <= 0x9f:
		return int(tag & 0x0f), pos + 1, nil
	case tag == 0xdc:
		n, err := readBigEndian(data, pos+1, 2)
		return int(n), pos + 3, err
	case tag == 0xdd:
		n, err := readBigEndian(data, pos+1, 4)
		return int(n), pos + 5, err
	}
	return 0, 0, fmt.Errorf("msgpack: expected an array at pos %d, got tag 0x%02x", pos, tag)
}

// ReadInt reads the small integer at pos (the only encodings the reference
// reads back: fixints and unsigned 8/16/32-bit).
func ReadInt(data []byte, pos int) (int64, error) {
	if pos >= len(data) {
		return 0, fmt.Errorf("msgpack: offset %d past end of blob", pos)
	}
	tag := data[pos]
	switch {
	case tag <= tagFixIntMax:
		return int64(tag), nil
	case tag == 0xcc:
		if pos+2 > len(data) {
			return 0, fmt.Errorf("msgpack: truncated uint8 at %d", pos)
		}
		return int64(data[pos+1]), nil
	case tag == 0xcd:
		n, err := readBigEndian(data, pos+1, 2)
		return int64(n), err
	case tag == 0xce:
		n, err := readBigEndian(data, pos+1, 4)
		return int64(n), err
	}
	return 0, fmt.Errorf("msgpack: read_int: unexpected tag 0x%02x at pos %d", tag, pos)
}

// ReadInt64At reads the payload of a 0xd3 value (int64, big endian).
func ReadInt64At(data []byte, pos int) (int64, error) {
	if pos >= len(data) || data[pos] != tagInt64 {
		var tag byte
		if pos < len(data) {
			tag = data[pos]
		}
		return 0, fmt.Errorf("msgpack: expected int64 (0xd3) at %d, got 0x%02x", pos, tag)
	}
	if pos+9 > len(data) {
		return 0, fmt.Errorf("msgpack: truncated int64 at %d", pos)
	}
	var value int64
	for i := 0; i < 8; i++ {
		value = value<<8 | int64(data[pos+1+i])
	}
	return value, nil
}

// WriteInt64At overwrites the payload of an existing 0xd3 value, leaving the tag
// and therefore the value's length untouched.
func WriteInt64At(data []byte, pos int, value int64) error {
	if pos >= len(data) || data[pos] != tagInt64 {
		return fmt.Errorf("msgpack: cannot write int64 at %d (tag is not 0xd3)", pos)
	}
	if pos+9 > len(data) {
		return fmt.Errorf("msgpack: truncated int64 at %d", pos)
	}
	for i := 0; i < 8; i++ {
		data[pos+1+i] = byte(value >> (8 * (7 - i)))
	}
	return nil
}

func readBigEndian(data []byte, pos, size int) (uint32, error) {
	if pos+size > len(data) {
		return 0, fmt.Errorf("msgpack: truncated length prefix at %d", pos)
	}
	var value uint32
	for i := 0; i < size; i++ {
		value = value<<8 | uint32(data[pos+i])
	}
	return value, nil
}

// ---------------------------------------------------------------- writing

// AppendStr appends a msgpack string (fixstr/str8/str16) exactly as
// msgpack-python does with use_bin_type=True.
func AppendStr(dst []byte, value string) []byte {
	n := len(value)
	switch {
	case n <= 31:
		dst = append(dst, byte(tagFixStr|n))
	case n <= 0xff:
		dst = append(dst, 0xd9, byte(n))
	case n <= 0xffff:
		dst = append(dst, 0xda, byte(n>>8), byte(n))
	default:
		dst = append(dst, 0xdb, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	return append(dst, value...)
}

// AppendInt appends an integer using the tightest encoding, matching
// msgpack-python's default integer packing (which is what the reference relies
// on for the rebuilt table-of-contents header).
func AppendInt(dst []byte, value int64) []byte {
	switch {
	case value >= 0 && value <= tagFixIntMax:
		return append(dst, byte(value))
	case value < 0 && value >= -32:
		return append(dst, byte(value))
	case value >= 0 && value <= 0xff:
		return append(dst, 0xcc, byte(value))
	case value >= 0 && value <= 0xffff:
		return append(dst, 0xcd, byte(value>>8), byte(value))
	case value >= 0 && value <= 0xffffffff:
		return append(dst, 0xce, byte(value>>24), byte(value>>16), byte(value>>8), byte(value))
	case value >= 0:
		return append(dst, 0xcf, byte(value>>56), byte(value>>48), byte(value>>40), byte(value>>32),
			byte(value>>24), byte(value>>16), byte(value>>8), byte(value))
	case value >= -128:
		return append(dst, 0xd0, byte(value))
	case value >= -32768:
		return append(dst, 0xd1, byte(value>>8), byte(value))
	case value >= -2147483648:
		return append(dst, 0xd2, byte(value>>24), byte(value>>16), byte(value>>8), byte(value))
	default:
		return append(dst, 0xd3, byte(value>>56), byte(value>>48), byte(value>>40), byte(value>>32),
			byte(value>>24), byte(value>>16), byte(value>>8), byte(value))
	}
}

// AppendArrayHeader appends the tightest array header for n elements.
func AppendArrayHeader(dst []byte, n int) []byte {
	switch {
	case n <= 0x0f:
		return append(dst, byte(tagFixArray|n))
	case n <= 0xffff:
		return append(dst, 0xdc, byte(n>>8), byte(n))
	default:
		return append(dst, 0xdd, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
}

// AppendMapHeader appends the tightest map header for n pairs.
func AppendMapHeader(dst []byte, n int) []byte {
	switch {
	case n <= 0x0f:
		return append(dst, byte(tagFixMapMin|n))
	case n <= 0xffff:
		return append(dst, 0xde, byte(n>>8), byte(n))
	default:
		return append(dst, 0xdf, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
}
