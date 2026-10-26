package mc

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"math"
	"os"
	"path/filepath"
	"testing"
)

// go test -run TestGenDemoWorld with DEMO_WORLD=<save folder> writes a procedural world (screenshots only).
func vnoise(x, z float64, seed int) float64 {
	h := func(i, j int) float64 {
		n := uint32(i*374761393+j*668265263+seed*144665) ^ 0x5bd1e995
		n = (n ^ (n >> 13)) * 1274126177
		return float64(n&0xffff) / 65535
	}
	xi, zi := int(math.Floor(x)), int(math.Floor(z))
	xf, zf := x-float64(xi), z-float64(zi)
	s := func(t float64) float64 { return t * t * (3 - 2*t) }
	a, b := h(xi, zi), h(xi+1, zi)
	c, d := h(xi, zi+1), h(xi+1, zi+1)
	return (a+(b-a)*s(xf))*(1-s(zf)) + (c+(d-c)*s(xf))*s(zf)
}

func fbm(x, z float64, seed int) float64 {
	v, amp, f := 0.0, 1.0, 1.0
	for o := 0; o < 5; o++ {
		v += amp * (vnoise(x*f, z*f, seed+o) - .5)
		amp *= .5
		f *= 2
	}
	return v
}

func demoColumn(wx, wz int) map[int]string {
	x, z := float64(wx), float64(wz)
	h := int(64 + 34*fbm(x/260, z/260, 1) + 8*fbm(x/60, z/60, 7))
	col := map[int]string{}
	for y := h - 6; y < h; y++ {
		col[y] = "stone"
	}
	switch {
	case h < 62:
		col[h] = "sand"
		for y := h + 1; y <= 62; y++ {
			col[y] = "water"
		}
		return col
	case h <= 64:
		col[h] = "sand"
	case h > 92:
		col[h] = "snow_block"
	case h > 84:
		col[h] = "stone"
	default:
		col[h] = "grass_block"
		cherry := fbm(x/140, z/140, 33) > .12
		dens := vnoise(x/3.1, z/3.1, 91)
		if fbm(x/90, z/90, 21) > -.02 && dens > .82 {
			leaf := "oak_leaves"
			if cherry {
				leaf = "cherry_leaves"
			}
			col[h+4] = leaf
		} else if math.Abs(fbm(x/180, z/180, 55)) < .012 {
			col[h] = "dirt_path"
		}
	}
	return col
}

func demoChunk(cx, cz int) []byte {
	minY := -64
	cols := map[[2]int]map[int]string{}
	for z := 0; z < 16; z++ {
		for x := 0; x < 16; x++ {
			cols[[2]int{x, z}] = demoColumn(cx*16+x, cz*16+z)
		}
	}
	var secs []tag
	for sy := 2; sy < 8; sy++ {
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
		bs := map[string]tag{"palette": {9, pl}}
		if len(pal) > 1 {
			bs["data"] = tag{12, pack(vals, 4, false)}
		}
		secs = append(secs, C(map[string]tag{"Y": {1, int8(sy)}, "block_states": C(bs)}))
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
	root := C(map[string]tag{"DataVersion": {3, int32(3953)}, "Status": {8, "minecraft:full"}, "yPos": {3, int32(-4)},
		"sections": {9, secs}, "Heightmaps": C(map[string]tag{"MOTION_BLOCKING": {12, pack(hv, 9, false)}})})
	w := &nb{}
	w.hdr(10, "")
	w.payload(root)
	var zb bytes.Buffer
	zw := zlib.NewWriter(&zb)
	zw.Write(w.Bytes())
	zw.Close()
	return zb.Bytes()
}

func TestGenDemoWorld(t *testing.T) {
	dir := os.Getenv("DEMO_WORLD")
	if dir == "" {
		t.Skip("set DEMO_WORLD")
	}
	for rx := -1; rx <= 0; rx++ {
		for rz := -1; rz <= 0; rz++ {
			hdr := make([]byte, 8192)
			var body bytes.Buffer
			sector := 2
			for cz := 0; cz < 32; cz++ {
				for cx := 0; cx < 32; cx++ {
					d := demoChunk(rx*32+cx, rz*32+cz)
					var e bytes.Buffer
					binary.Write(&e, binary.BigEndian, uint32(len(d)+1))
					e.WriteByte(2)
					e.Write(d)
					for e.Len()%4096 != 0 {
						e.WriteByte(0)
					}
					n := e.Len() / 4096
					binary.BigEndian.PutUint32(hdr[(cz*32+cx)*4:], uint32(sector<<8|n))
					body.Write(e.Bytes())
					sector += n
				}
			}
			p := filepath.Join(dir, "region", "r."+itoa(rx)+"."+itoa(rz)+".mca")
			os.MkdirAll(filepath.Dir(p), 0o755)
			os.WriteFile(p, append(hdr, body.Bytes()...), 0o644)
		}
	}
}
