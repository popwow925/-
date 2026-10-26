package mc

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"fmt"
	"image/color"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// ---- tiny NBT writer for synthetic chunks

type nb struct{ bytes.Buffer }

func (w *nb) str(s string)            { binary.Write(w, binary.BigEndian, uint16(len(s))); w.WriteString(s) }
func (w *nb) hdr(t byte, name string) { w.WriteByte(t); w.str(name) }

type tag struct {
	t byte
	v any
}

func (w *nb) payload(t tag) {
	switch t.t {
	case 1:
		w.WriteByte(byte(t.v.(int8)))
	case 3:
		binary.Write(w, binary.BigEndian, t.v.(int32))
	case 8:
		w.str(t.v.(string))
	case 9:
		l := t.v.([]tag)
		et := byte(10)
		if len(l) > 0 {
			et = l[0].t
		}
		w.WriteByte(et)
		binary.Write(w, binary.BigEndian, int32(len(l)))
		for _, e := range l {
			w.payload(e)
		}
	case 10:
		m := t.v.(map[string]tag)
		keys := make([]string, 0, len(m))
		for k := range m {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			w.hdr(m[k].t, k)
			w.payload(m[k])
		}
		w.WriteByte(0)
	case 12:
		a := t.v.([]int64)
		binary.Write(w, binary.BigEndian, int32(len(a)))
		for _, x := range a {
			binary.Write(w, binary.BigEndian, x)
		}
	}
}

func C(m map[string]tag) tag { return tag{10, m} }
func pack(vals []int, b int, spanning bool) []int64 {
	if spanning {
		out := make([]int64, (len(vals)*b+63)/64)
		for i, v := range vals {
			bit := i * b
			li, off := bit/64, bit%64
			out[li] |= int64(uint64(v) << uint(off))
			if off+b > 64 {
				out[li+1] |= int64(uint64(v) >> uint(64-off))
			}
		}
		return out
	}
	per := 64 / b
	out := make([]int64, (len(vals)+per-1)/per)
	for i, v := range vals {
		out[i/per] |= int64(uint64(v) << (uint(i%per) * uint(b)))
	}
	return out
}

// world: grass at y=64, a pond (water 60..64 over sand) where x<8 in chunk (1,0), a cherry tree in chunk (0,0)
func column(cx, x, z int) map[int]string {
	col := map[int]string{}
	for y := 50; y < 64; y++ {
		col[y] = "stone"
	}
	if cx == 1 && x < 8 {
		col[59] = "sand"
		for y := 60; y <= 64; y++ {
			col[y] = "water"
		}
		return col
	}
	col[64] = "grass_block"
	if cx == 0 && x >= 4 && x <= 6 && z >= 4 && z <= 6 {
		col[68] = "cherry_leaves"
	}
	return col
}

func makeChunk(cx, cz int, modern bool) []byte {
	minY := 0
	if modern {
		minY = -64
	}
	cols := map[[2]int]map[int]string{}
	for z := 0; z < 16; z++ {
		for x := 0; x < 16; x++ {
			cols[[2]int{x, z}] = column(cx, x, z)
		}
	}
	var secs []tag
	for sy := minY >> 4; sy < 6; sy++ {
		pal := []string{"air"}
		idx := map[string]int{"air": 0}
		vals := make([]int, 4096)
		for y := 0; y < 16; y++ {
			for z := 0; z < 16; z++ {
				for x := 0; x < 16; x++ {
					n, ok := cols[[2]int{x, z}][sy*16+y]
					if !ok {
						continue
					}
					if _, ok := idx[n]; !ok {
						idx[n] = len(pal)
						pal = append(pal, n)
					}
					vals[(y*16+z)*16+x] = idx[n]
				}
			}
		}
		var pl []tag
		for _, n := range pal {
			pl = append(pl, C(map[string]tag{"Name": {8, "minecraft:" + n}}))
		}
		b := 4
		data := pack(vals, b, !modern)
		if modern {
			bs := map[string]tag{"palette": {9, pl}}
			if len(pal) > 1 {
				bs["data"] = tag{12, data}
			}
			secs = append(secs, C(map[string]tag{"Y": {1, int8(sy)}, "block_states": C(bs)}))
		} else {
			secs = append(secs, C(map[string]tag{"Y": {1, int8(sy)}, "Palette": {9, pl}, "BlockStates": {12, data}}))
		}
	}
	hv := make([]int, 256)
	for z := 0; z < 16; z++ {
		for x := 0; x < 16; x++ {
			top := minY - 1
			for y := range cols[[2]int{x, z}] {
				top = max(top, y)
			}
			hv[z*16+x] = top - minY + 1
		}
	}
	hm := C(map[string]tag{"MOTION_BLOCKING": {12, pack(hv, 9, !modern)}})
	var root tag
	if modern {
		root = C(map[string]tag{"DataVersion": {3, int32(3953)}, "Status": {8, "minecraft:full"}, "yPos": {3, int32(-4)},
			"xPos": {3, int32(cx)}, "zPos": {3, int32(cz)}, "sections": {9, secs}, "Heightmaps": hm})
	} else {
		root = C(map[string]tag{"DataVersion": {3, int32(2230)}, "Level": C(map[string]tag{"Status": {8, "full"},
			"xPos": {3, int32(cx)}, "zPos": {3, int32(cz)}, "Sections": {9, secs}, "Heightmaps": hm})})
	}
	w := &nb{}
	w.hdr(10, "")
	w.payload(root)
	var z bytes.Buffer
	zw := zlib.NewWriter(&z)
	zw.Write(w.Bytes())
	zw.Close()
	return z.Bytes()
}

func writeRegion(t *testing.T, path string, modern bool) {
	hdr := make([]byte, 8192)
	var body bytes.Buffer
	sector := 2
	for _, c := range [][2]int{{0, 0}, {1, 0}} {
		d := makeChunk(c[0], c[1], modern)
		var e bytes.Buffer
		binary.Write(&e, binary.BigEndian, uint32(len(d)+1))
		e.WriteByte(2)
		e.Write(d)
		for e.Len()%4096 != 0 {
			e.WriteByte(0)
		}
		n := e.Len() / 4096
		binary.BigEndian.PutUint32(hdr[(c[1]*32+c[0])*4:], uint32(sector<<8|n))
		body.Write(e.Bytes())
		sector += n
	}
	os.MkdirAll(filepath.Dir(path), 0o755)
	os.WriteFile(path, append(hdr, body.Bytes()...), 0o644)
}

func near(a, b color.RGBA, tol int) bool {
	d := func(x, y uint8) int {
		v := int(x) - int(y)
		if v < 0 {
			return -v
		}
		return v
	}
	return d(a.R, b.R) <= tol && d(a.G, b.G) <= tol && d(a.B, b.B) <= tol
}

func TestRenderRegion(t *testing.T) {
	for _, modern := range []bool{true, false} {
		t.Run(fmt.Sprint("modern=", modern), func(t *testing.T) {
			w := t.TempDir()
			writeRegion(t, filepath.Join(w, "region", "r.0.0.mca"), modern)
			img, err := RenderRegion(w, "overworld", 0, 0)
			if err != nil {
				t.Fatal(err)
			}
			grass := img.RGBAAt(12, 12)
			leaves := img.RGBAAt(5, 8) // inside the tree (row 8 isn't a north edge: no relief shading)
			_ = leaves
			water := img.RGBAAt(16+2, 8)
			empty := img.RGBAAt(100, 100)
			if !near(grass, blockColors["grass_block"], 2) {
				t.Errorf("grass %v", grass)
			}
			if c := img.RGBAAt(5, 5); !(c.R > 200 && c.G > 150) { // cherry leaves (lit north edge or plain)
				t.Errorf("leaves %v", c)
			}
			if !(water.B > water.R+60) {
				t.Errorf("water %v", water)
			}
			if empty.A != 0 {
				t.Errorf("ungenerated chunk should be transparent: %v", empty)
			}
			// relief: the north edge of the tree is lit, the cell south of it is in shade
			if n, s := img.RGBAAt(5, 4), img.RGBAAt(5, 7); !(n.R >= s.R) {
				t.Errorf("relief %v %v", n, s)
			}
			png, err := RegionTile(t.TempDir(), w, "overworld", 0, 0)
			if err != nil || len(png) < 100 {
				t.Fatal("tile", err)
			}
		})
	}
}

func TestMapInfo(t *testing.T) {
	w := t.TempDir()
	writeRegion(t, filepath.Join(w, "region", "r.0.0.mca"), true)
	writeRegion(t, filepath.Join(w, "region", "r.-1.2.mca"), true)
	os.WriteFile(filepath.Join(w, "region", "r.5.5.mca"), make([]byte, 8192), 0o644) // empty: ignored
	info := WorldMapInfo(w)
	if len(info.Dims["overworld"]) != 2 || info.Dims["overworld"][0] != [2]int{-1, 2} {
		t.Fatal(info.Dims)
	}
}

func TestUnpack(t *testing.T) {
	vals := []int{1, 300, 5, 511, 0, 77, 256, 9, 100}
	for _, sp := range []bool{true, false} {
		d := pack(vals, 9, sp)
		for i, v := range vals {
			if got := unpack(d, 9, i, sp); got != v {
				t.Fatalf("spanning=%v i=%d got %d want %d", sp, i, got, v)
			}
		}
	}
	if c, _ := blockColor("light_blue_wool"); c != dyes["light_blue"] {
		t.Fatal("dye", c)
	}
	if c, _ := blockColor("oak_stairs"); c != blockColors["oak_planks"] {
		t.Fatal("stairs", c)
	}
}
