package ltmd

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/hex"
	"fmt"
	"os"

	"github.com/pierrec/lz4/v4"
	"github.com/vmihailenco/msgpack/v5"
)

// Container crypto parameters. These are the same constants the upstream Go
// server uses to read the file (internal/masterdata/memorydb/memorydb.go) and
// the same ones the Python patcher writes with - they are baked into the client,
// not configurable.
const (
	DefaultKeyHex = "36436230313332314545356536624265"
	DefaultIVHex  = "45666341656634434165356536446141"
	LZ4ExtCode    = int8(99)
)

// KeyIV returns the AES key and IV as raw bytes.
func KeyIV(keyHex, ivHex string) ([]byte, []byte, error) {
	key, err := hex.DecodeString(keyHex)
	if err != nil {
		return nil, nil, fmt.Errorf("decode key: %w", err)
	}
	if len(key) != 16 && len(key) != 32 {
		return nil, nil, fmt.Errorf("AES key must be 16 or 32 bytes, got %d", len(key))
	}
	iv, err := hex.DecodeString(ivHex)
	if err != nil {
		return nil, nil, fmt.Errorf("decode iv: %w", err)
	}
	if len(iv) != 16 {
		return nil, nil, fmt.Errorf("AES IV must be 16 bytes, got %d", len(iv))
	}
	return key, iv, nil
}

// Container is a decrypted master-data file: an ordered table of contents plus
// the concatenated table blobs.
type Container struct {
	// Order is the TOC order in the file (which the reference re-sorts by
	// original offset when rebuilding).
	Order []string
	// TOC maps a table name to its (offset, length) inside Blob.
	TOC  map[string][2]int
	Blob []byte

	// NewHeader is the rebuilt TOC header produced by Patch. It is empty until
	// Patch has run.
	NewHeader []byte
}

// Plaintext returns the header followed by the data blob - the bytes that get
// encrypted on the way out.
func (c *Container) Plaintext() []byte {
	out := make([]byte, 0, len(c.NewHeader)+len(c.Blob))
	out = append(out, c.NewHeader...)
	return append(out, c.Blob...)
}

// ReadFile decrypts and parses a .bin.e file.
func ReadFile(path, keyHex, ivHex string) (*Container, error) {
	encrypted, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	key, iv, err := KeyIV(keyHex, ivHex)
	if err != nil {
		return nil, err
	}
	plain, err := Decrypt(encrypted, key, iv)
	if err != nil {
		return nil, err
	}
	return Parse(plain)
}

// Parse splits decrypted bytes into the TOC header and the data blob. The header
// is the first msgpack object; everything after it is the blob.
func Parse(plain []byte) (*Container, error) {
	reader := bytes.NewReader(plain)
	decoder := msgpack.NewDecoder(reader)
	decoder.UseLooseInterfaceDecoding(true)

	var header map[string]interface{}
	if err := decoder.Decode(&header); err != nil {
		return nil, fmt.Errorf("decode header: %w", err)
	}
	blobStart := len(plain) - reader.Len()

	toc := make(map[string][2]int, len(header))
	order := make([]string, 0, len(header))
	for name, value := range header {
		pair, ok := value.([]interface{})
		if !ok || len(pair) != 2 {
			return nil, fmt.Errorf("table %q: TOC entry is %T, expected [offset, length]", name, value)
		}
		offset, err1 := toInt64(pair[0])
		length, err2 := toInt64(pair[1])
		if err1 != nil || err2 != nil {
			return nil, fmt.Errorf("table %q: bad TOC entry %v", name, pair)
		}
		toc[name] = [2]int{int(offset), int(length)}
		order = append(order, name)
	}
	return &Container{Order: order, TOC: toc, Blob: plain[blobStart:]}, nil
}

// Table returns the raw blob bytes of one table.
func (c *Container) Table(name string) ([]byte, bool) {
	entry, ok := c.TOC[name]
	if !ok {
		return nil, false
	}
	if entry[0]+entry[1] > len(c.Blob) {
		return nil, false
	}
	return c.Blob[entry[0] : entry[0]+entry[1]], true
}

// TableBytes decodes a table into a mutable, decompressed blob: LZ4 ext blobs
// are decompressed, plain tables are returned as-is.
func (c *Container) TableBytes(name string) (data []byte, compressed bool, err error) {
	raw, ok := c.Table(name)
	if !ok {
		return nil, false, fmt.Errorf("table %q is not in the TOC", name)
	}
	return DecodeTable(raw)
}

// DecodeTable unwraps the LZ4 ExtType(99) wrapper when present.
//
// The ext envelope is parsed by hand: msgpack/v5 has no exported Ext type, and
// the wire format is unambiguous - 0xc7/0xc8/0xc9 carry a 1/2/4-byte length then
// the type byte, and non-99 extensions are not tables, so they fall through as
// raw bytes exactly like the reference does.
func DecodeTable(raw []byte) (data []byte, compressed bool, err error) {
	if len(raw) == 0 {
		return raw, false, nil
	}
	var payload []byte
	var code int8
	switch tag := raw[0]; tag {
	case 0xc7: // ext 8
		if len(raw) < 3 {
			return nil, false, fmt.Errorf("ext: truncated header")
		}
		n := int(raw[1])
		if len(raw) < 3+n {
			return nil, false, fmt.Errorf("ext: declared %d bytes, only %d present", n, len(raw)-3)
		}
		code, payload = int8(raw[2]), raw[3:3+n]
	case 0xc8: // ext 16
		if len(raw) < 4 {
			return nil, false, fmt.Errorf("ext: truncated header")
		}
		n := int(raw[1])<<8 | int(raw[2])
		if len(raw) < 4+n {
			return nil, false, fmt.Errorf("ext: declared %d bytes, only %d present", n, len(raw)-4)
		}
		code, payload = int8(raw[3]), raw[4:4+n]
	case 0xc9: // ext 32
		if len(raw) < 6 {
			return nil, false, fmt.Errorf("ext: truncated header")
		}
		n := int(raw[1])<<24 | int(raw[2])<<16 | int(raw[3])<<8 | int(raw[4])
		if len(raw) < 6+n {
			return nil, false, fmt.Errorf("ext: declared %d bytes, only %d present", n, len(raw)-6)
		}
		code, payload = int8(raw[5]), raw[6:6+n]
	default:
		return raw, false, nil
	}

	if code != LZ4ExtCode {
		return raw, false, nil
	}
	uncompressedLen, compressedBlock, err := splitLZ4ExtHeader(payload)
	if err != nil {
		return nil, true, err
	}
	out := make([]byte, uncompressedLen)
	n, err := lz4.UncompressBlock(compressedBlock, out)
	if err != nil || n == 0 {
		// Incompressible tables are stored raw inside the ext payload.
		if len(compressedBlock) == uncompressedLen {
			copy(out, compressedBlock)
			return out, true, nil
		}
		if err != nil {
			return nil, true, fmt.Errorf("lz4: %w", err)
		}
		return nil, true, fmt.Errorf("lz4: decompressed 0 bytes for %d expected", uncompressedLen)
	}
	return out[:n], true, nil
}

// EncodeTable re-wraps a table blob, compressing it when the original was
// compressed (mirrors build_lz4_ext_blob).
func EncodeTable(data []byte, compressed bool) ([]byte, error) {
	if !compressed {
		return data, nil
	}
	dest := make([]byte, lz4.CompressBlockBound(len(data)))
	n, err := lz4.CompressBlock(data, dest, nil)
	if err != nil {
		return nil, fmt.Errorf("lz4: %w", err)
	}
	var payload []byte
	if n == 0 {
		// Not compressible: store the block as-is, the way the reference's
		// library does, and rely on the length prefix to detect it.
		payload = data
	} else {
		payload = dest[:n]
	}

	// msgpack ExtType(code=99, data = int32 length prefix + lz4 bytes), which
	// msgpack-python packs as ext8/ext16/ext32 by payload size.
	body := make([]byte, 0, 5+len(payload))
	body = append(body, 0xd2) // int32 tag, part of the C# payload convention
	l := len(data)
	body = append(body, byte(l>>24), byte(l>>16), byte(l>>8), byte(l))
	body = append(body, payload...)
	return packExt(LZ4ExtCode, body), nil
}

func splitLZ4ExtHeader(extData []byte) (int, []byte, error) {
	if len(extData) == 0 {
		return 0, nil, fmt.Errorf("lz4 ext: empty payload")
	}
	tag := extData[0]
	switch {
	case tag == 0xd2:
		if len(extData) < 5 {
			return 0, nil, fmt.Errorf("lz4 ext: truncated int32 length")
		}
		v := int32(uint32(extData[1])<<24 | uint32(extData[2])<<16 | uint32(extData[3])<<8 | uint32(extData[4]))
		return int(v), extData[5:], nil
	case tag == 0xce:
		if len(extData) < 5 {
			return 0, nil, fmt.Errorf("lz4 ext: truncated uint32 length")
		}
		v := uint32(extData[1])<<24 | uint32(extData[2])<<16 | uint32(extData[3])<<8 | uint32(extData[4])
		return int(v), extData[5:], nil
	case tag == 0xd1:
		if len(extData) < 3 {
			return 0, nil, fmt.Errorf("lz4 ext: truncated int16 length")
		}
		v := int16(uint16(extData[1])<<8 | uint16(extData[2]))
		return int(v), extData[3:], nil
	case tag == 0xcd:
		if len(extData) < 3 {
			return 0, nil, fmt.Errorf("lz4 ext: truncated uint16 length")
		}
		v := uint16(extData[1])<<8 | uint16(extData[2])
		return int(v), extData[3:], nil
	case tag <= 0x7f:
		return int(tag), extData[1:], nil
	}
	return 0, nil, fmt.Errorf("lz4 ext: unexpected msgpack tag 0x%02x", tag)
}

// packExt encodes msgpack ExtType(code, data) the way msgpack-python does.
func packExt(code int8, data []byte) []byte {
	var out []byte
	n := len(data)
	switch {
	case n <= 0xff:
		out = append(out, 0xc7, byte(n))
	case n <= 0xffff:
		out = append(out, 0xc8, byte(n>>8), byte(n))
	default:
		out = append(out, 0xc9, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	out = append(out, byte(code))
	return append(out, data...)
}

// Encrypt applies AES-CBC with PKCS#7 padding.
func Encrypt(plain, key, iv []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	padded := pkcs7Pad(plain, block.BlockSize())
	out := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(out, padded)
	return out, nil
}

// Decrypt reverses Encrypt.
func Decrypt(encrypted, key, iv []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	if len(encrypted)%block.BlockSize() != 0 {
		return nil, fmt.Errorf("ciphertext length %d is not a multiple of the block size", len(encrypted))
	}
	out := make([]byte, len(encrypted))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(out, encrypted)
	return pkcs7Unpad(out, block.BlockSize())
}

func pkcs7Pad(data []byte, blockSize int) []byte {
	pad := blockSize - len(data)%blockSize
	return append(data, bytes.Repeat([]byte{byte(pad)}, pad)...)
}

func pkcs7Unpad(data []byte, blockSize int) ([]byte, error) {
	if len(data) == 0 {
		return nil, fmt.Errorf("empty plaintext")
	}
	pad := int(data[len(data)-1])
	if pad == 0 || pad > blockSize || pad > len(data) {
		return nil, fmt.Errorf("invalid padding length %d", pad)
	}
	for _, b := range data[len(data)-pad:] {
		if int(b) != pad {
			return nil, fmt.Errorf("invalid padding byte")
		}
	}
	return data[:len(data)-pad], nil
}

// BuildHeader encodes the TOC the way the reference does: a msgpack map of
// name -> [offset, length], with the keys in the given order.
func BuildHeader(order []string, lookup func(string) [2]int) []byte {
	out := AppendMapHeader(nil, len(order))
	for _, name := range order {
		out = AppendStr(out, name)
		entry := lookup(name)
		out = AppendArrayHeader(out, 2)
		out = AppendInt(out, int64(entry[0]))
		out = AppendInt(out, int64(entry[1]))
	}
	return out
}

// WriteFile re-encrypts and writes the container.
func WriteFile(path string, header, blob, key, iv []byte) error {
	plain := make([]byte, 0, len(header)+len(blob))
	plain = append(plain, header...)
	plain = append(plain, blob...)
	encrypted, err := Encrypt(plain, key, iv)
	if err != nil {
		return err
	}
	return os.WriteFile(path, encrypted, 0o644)
}

func toInt64(value interface{}) (int64, error) {
	switch v := value.(type) {
	case int:
		return int64(v), nil
	case int8:
		return int64(v), nil
	case int16:
		return int64(v), nil
	case int32:
		return int64(v), nil
	case int64:
		return v, nil
	case uint:
		return int64(v), nil
	case uint8:
		return int64(v), nil
	case uint16:
		return int64(v), nil
	case uint32:
		return int64(v), nil
	case uint64:
		return int64(v), nil
	}
	return 0, fmt.Errorf("not an integer: %T", value)
}
