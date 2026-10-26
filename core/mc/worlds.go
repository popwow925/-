package mc

import (
	"archive/zip"
	"bufio"
	"compress/gzip"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// ---------------------------------------------------------------- minimal NBT reader

// ReadNBT parses a gzip-compressed NBT file (level.dat) into nested maps.
func ReadNBT(path string) (map[string]any, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var r io.Reader = bufio.NewReader(f)
	if gz, err := gzip.NewReader(r); err == nil {
		r = gz
	} else {
		f.Seek(0, 0)
		r = bufio.NewReader(f)
	}
	nr := &nbtReader{r: r}
	typ := nr.u8()
	if typ != 10 {
		return nil, errors.New("不是 NBT 文件")
	}
	nr.str() // root name
	v := nr.payload(10, 0)
	if nr.err != nil {
		return nil, nr.err
	}
	m, _ := v.(map[string]any)
	return m, nil
}

type nbtReader struct {
	r    io.Reader
	err  error
	buf  [8]byte
	keep bool // keep int/long arrays (chunk data); level.dat readers skip them
}

func (n *nbtReader) read(k int) []byte {
	if n.err != nil {
		return n.buf[:k]
	}
	_, n.err = io.ReadFull(n.r, n.buf[:k])
	return n.buf[:k]
}
func (n *nbtReader) u8() byte   { return n.read(1)[0] }
func (n *nbtReader) i16() int16 { return int16(binary.BigEndian.Uint16(n.read(2))) }
func (n *nbtReader) i32() int32 { return int32(binary.BigEndian.Uint32(n.read(4))) }
func (n *nbtReader) i64() int64 { return int64(binary.BigEndian.Uint64(n.read(8))) }
func (n *nbtReader) str() string {
	l := int(uint16(n.i16()))
	if n.err != nil || l == 0 {
		return ""
	}
	b := make([]byte, l)
	_, n.err = io.ReadFull(n.r, b)
	return string(b)
}

func (n *nbtReader) skip(k int64) {
	if n.err == nil && k > 0 {
		_, n.err = io.CopyN(io.Discard, n.r, k)
	}
}

func (n *nbtReader) payload(t byte, depth int) any {
	if n.err != nil || depth > 64 {
		if n.err == nil {
			n.err = errors.New("NBT 嵌套过深")
		}
		return nil
	}
	switch t {
	case 1:
		return int8(n.u8())
	case 2:
		return n.i16()
	case 3:
		return n.i32()
	case 4:
		return n.i64()
	case 5:
		return math.Float32frombits(uint32(n.i32()))
	case 6:
		return math.Float64frombits(uint64(n.i64()))
	case 7: // byte array: skipped (chunk-sized data we never show)
		n.skip(int64(n.i32()))
		return nil
	case 8:
		return n.str()
	case 9:
		et := n.u8()
		l := int(n.i32())
		if l < 0 || l > 1<<20 {
			n.err = errors.New("NBT 列表过长")
			return nil
		}
		out := make([]any, 0, min(l, 64))
		for i := 0; i < l && n.err == nil; i++ {
			out = append(out, n.payload(et, depth+1))
		}
		return out
	case 10:
		m := map[string]any{}
		for n.err == nil {
			ct := n.u8()
			if ct == 0 {
				break
			}
			name := n.str()
			m[name] = n.payload(ct, depth+1)
		}
		return m
	case 11:
		l := int64(n.i32())
		if !n.keep || l < 0 || l > 1<<16 {
			n.skip(l * 4)
			return nil
		}
		b := make([]byte, l*4)
		if _, n.err = io.ReadFull(n.r, b); n.err != nil {
			return nil
		}
		out := make([]int32, l)
		for i := range out {
			out[i] = int32(binary.BigEndian.Uint32(b[i*4:]))
		}
		return out
	case 12:
		l := int64(n.i32())
		if !n.keep || l < 0 || l > 1<<16 {
			n.skip(l * 8)
			return nil
		}
		b := make([]byte, l*8)
		if _, n.err = io.ReadFull(n.r, b); n.err != nil {
			return nil
		}
		out := make([]int64, l)
		for i := range out {
			out[i] = int64(binary.BigEndian.Uint64(b[i*8:]))
		}
		return out
	}
	n.err = fmt.Errorf("未知 NBT 类型 %d", t)
	return nil
}

func nbtPath(m map[string]any, path ...string) any {
	var cur any = m
	for _, p := range path {
		mm, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = mm[p]
	}
	return cur
}

func nbtInt(v any) int64 {
	switch x := v.(type) {
	case int8:
		return int64(x)
	case int16:
		return int64(x)
	case int32:
		return int64(x)
	case int64:
		return x
	}
	return 0
}

// ---------------------------------------------------------------- worlds

type World struct {
	Folder     string `json:"folder"`
	Name       string `json:"name"`
	Mode       string `json:"mode"`
	Hardcore   bool   `json:"hardcore"`
	Cheats     bool   `json:"cheats"`
	LastPlayed int64  `json:"lastPlayed"` // unix seconds
	Version    string `json:"version"`
	Size       int64  `json:"size"`
	HasIcon    bool   `json:"hasIcon"`
	Seed       string `json:"seed,omitempty"`
	Day        int64  `json:"day"`
	Error      string `json:"error,omitempty"`
}

var gameModes = map[int64]string{0: "生存", 1: "创造", 2: "冒险", 3: "旁观"}

// ListWorlds reads every save of an instance.
func ListWorlds(runDir string) []World {
	dir := filepath.Join(runDir, "saves")
	ents, _ := os.ReadDir(dir)
	var out []World
	for _, e := range ents {
		if !e.IsDir() {
			continue
		}
		wd := filepath.Join(dir, e.Name())
		w := World{Folder: e.Name(), Name: e.Name()}
		if _, err := os.Stat(filepath.Join(wd, "icon.png")); err == nil {
			w.HasIcon = true
		}
		w.Size = dirSize(wd)
		level := filepath.Join(wd, "level.dat")
		m, err := ReadNBT(level)
		if err != nil {
			if _, e2 := os.Stat(level); e2 != nil {
				continue // not a world folder
			}
			w.Error = "level.dat 已损坏"
			out = append(out, w)
			continue
		}
		d, _ := m["Data"].(map[string]any)
		if d == nil {
			d = m
		}
		if s, ok := d["LevelName"].(string); ok && s != "" {
			w.Name = s
		}
		w.Mode = gameModes[nbtInt(d["GameType"])]
		w.Hardcore = nbtInt(d["hardcore"]) == 1
		w.Cheats = nbtInt(d["allowCommands"]) == 1
		if lp := nbtInt(d["LastPlayed"]); lp > 0 {
			w.LastPlayed = lp / 1000
		} else if st, err := os.Stat(level); err == nil {
			w.LastPlayed = st.ModTime().Unix()
		}
		if s, ok := nbtPath(d, "Version", "Name").(string); ok {
			w.Version = s
		}
		seed := nbtInt(nbtPath(d, "WorldGenSettings", "seed"))
		if seed == 0 {
			seed = nbtInt(d["RandomSeed"])
		}
		if seed != 0 {
			w.Seed = fmt.Sprint(seed)
		}
		w.Day = nbtInt(d["DayTime"]) / 24000
		out = append(out, w)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].LastPlayed > out[j].LastPlayed })
	return out
}

func dirSize(p string) int64 {
	var n int64
	_ = filepath.WalkDir(p, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if i, err := d.Info(); err == nil {
				n += i.Size()
			}
		}
		return nil
	})
	return n
}

// WorldDir returns the folder of a save, refusing names that escape saves/.
func WorldDir(runDir, folder string) (string, error) {
	p, ok := within(filepath.Join(runDir, "saves"), folder)
	if !ok || strings.ContainsAny(folder, `/\`) {
		return "", errors.New("非法存档名")
	}
	return p, nil
}

// BackupWorld zips a save into <runDir>/backups and returns the zip path.
func BackupWorld(ctx context.Context, runDir, folder string) (string, error) {
	src, err := WorldDir(runDir, folder)
	if err != nil {
		return "", err
	}
	dst := filepath.Join(runDir, "backups", folder+"-"+time.Now().Format("20060102-150405")+".zip")
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", err
	}
	out, err := os.Create(dst)
	if err != nil {
		return "", err
	}
	zw := zip.NewWriter(out)
	err = filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if d.IsDir() || d.Name() == "session.lock" {
			return nil
		}
		rel, _ := filepath.Rel(filepath.Dir(src), p)
		w, err := zw.Create(filepath.ToSlash(rel))
		if err != nil {
			return err
		}
		in, err := os.Open(p)
		if err != nil {
			return nil // file locked by the running game: skip
		}
		_, err = io.Copy(w, in)
		in.Close()
		return err
	})
	if cerr := zw.Close(); err == nil {
		err = cerr
	}
	out.Close()
	if err != nil {
		os.Remove(dst)
		return "", err
	}
	return dst, nil
}

func DeleteWorld(runDir, folder string) error {
	p, err := WorldDir(runDir, folder)
	if err != nil {
		return err
	}
	return os.RemoveAll(p)
}
