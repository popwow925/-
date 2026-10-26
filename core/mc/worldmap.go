package mc

import (
	"bytes"
	"compress/gzip"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"math/bits"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// ---------------------------------------------------------------- dimensions & regions

var dimDirs = map[string]string{"overworld": "region", "nether": filepath.Join("DIM-1", "region"), "end": filepath.Join("DIM1", "region")}

var reRegion = regexp.MustCompile(`^r\.(-?\d+)\.(-?\d+)\.mca$`)

type MapInfo struct {
	Dims    map[string][][2]int `json:"dims"`   // dimension -> region coords
	Player  *MapPoint           `json:"player"` // last position of the single-player
	Spawn   *MapPoint           `json:"spawn"`
	Version string              `json:"version"`
	Legacy  bool                `json:"legacy"` // pre-1.13 world: block ids we can't colour
}

type MapPoint struct {
	X   float64 `json:"x"`
	Z   float64 `json:"z"`
	Dim string  `json:"dim"`
}

func WorldMapInfo(worldDir string) MapInfo {
	info := MapInfo{Dims: map[string][][2]int{}}
	for dim, rel := range dimDirs {
		ents, _ := os.ReadDir(filepath.Join(worldDir, rel))
		var list [][2]int
		for _, e := range ents {
			m := reRegion.FindStringSubmatch(e.Name())
			if m == nil {
				continue
			}
			if st, err := e.Info(); err != nil || st.Size() <= 8192 {
				continue // header only: nothing generated
			}
			x, _ := strconv.Atoi(m[1])
			z, _ := strconv.Atoi(m[2])
			list = append(list, [2]int{x, z})
		}
		if len(list) > 0 {
			sort.Slice(list, func(i, j int) bool { return list[i][0]*1e6+list[i][1] < list[j][0]*1e6+list[j][1] })
			info.Dims[dim] = list
		}
	}
	if lv, err := ReadNBT(filepath.Join(worldDir, "level.dat")); err == nil {
		d, _ := lv["Data"].(map[string]any)
		if d != nil {
			info.Spawn = &MapPoint{X: float64(nbtInt(d["SpawnX"])), Z: float64(nbtInt(d["SpawnZ"])), Dim: "overworld"}
			if s, ok := nbtPath(d, "Version", "Name").(string); ok {
				info.Version = s
			}
			info.Legacy = nbtInt(d["DataVersion"]) > 0 && nbtInt(d["DataVersion"]) < 1451
			if nbtInt(d["DataVersion"]) == 0 && info.Version == "" {
				info.Legacy = true
			}
			if p, ok := d["Player"].(map[string]any); ok {
				if pos, ok := p["Pos"].([]any); ok && len(pos) == 3 {
					x, _ := pos[0].(float64)
					z, _ := pos[2].(float64)
					dim := "overworld"
					switch v := p["Dimension"].(type) {
					case string:
						if strings.Contains(v, "nether") {
							dim = "nether"
						} else if strings.Contains(v, "end") {
							dim = "end"
						}
					case int32:
						dim = map[int32]string{-1: "nether", 1: "end"}[v]
						if dim == "" {
							dim = "overworld"
						}
					}
					info.Player = &MapPoint{X: x, Z: z, Dim: dim}
				}
			}
		}
	}
	return info
}

// ---------------------------------------------------------------- chunk decoding

type section struct {
	pal      []string
	data     []int64
	bits     int
	spanning bool
}

func (s *section) at(i int) string {
	if len(s.pal) == 0 {
		return "air"
	}
	if len(s.pal) == 1 || len(s.data) == 0 {
		return s.pal[0]
	}
	v := unpack(s.data, s.bits, i, s.spanning)
	if v < 0 || v >= len(s.pal) {
		return "air"
	}
	return s.pal[v]
}

func unpack(data []int64, b, i int, spanning bool) int {
	if b <= 0 {
		return 0
	}
	mask := uint64(1)<<b - 1
	if !spanning {
		per := 64 / b
		li := i / per
		if li >= len(data) {
			return -1
		}
		return int(uint64(data[li]) >> (uint(i%per) * uint(b)) & mask)
	}
	bit := i * b
	li, off := bit/64, bit%64
	if li >= len(data) {
		return -1
	}
	v := uint64(data[li]) >> uint(off)
	if off+b > 64 && li+1 < len(data) {
		v |= uint64(data[li+1]) << uint(64-off)
	}
	return int(v & mask)
}

type chunk struct {
	sections map[int]*section
	height   []int64 // MOTION_BLOCKING heightmap (packed)
	hbits    int
	hspan    bool
	minY     int
}

func (c *chunk) block(x, y, z int) string {
	sy := y >> 4
	s := c.sections[sy]
	if s == nil {
		return "air"
	}
	return s.at(((y&15)*16+z)*16 + x)
}

func (c *chunk) top(x, z int) (int, bool) {
	if len(c.height) == 0 {
		return 0, false
	}
	v := unpack(c.height, c.hbits, z*16+x, c.hspan)
	if v <= 0 {
		return 0, false
	}
	return c.minY + v - 1, true
}

func paletteNames(list any) []string {
	l, _ := list.([]any)
	out := make([]string, len(l))
	for i, e := range l {
		m, _ := e.(map[string]any)
		n, _ := m["Name"].(string)
		out[i] = strings.TrimPrefix(n, "minecraft:")
	}
	return out
}

func parseChunk(root map[string]any) *chunk {
	dv := int(nbtInt(root["DataVersion"]))
	spanning := dv > 0 && dv < 2556
	c := &chunk{sections: map[int]*section{}}
	lvl := root
	if l, ok := root["Level"].(map[string]any); ok { // 1.13–1.17
		lvl = l
	}
	if st, ok := lvl["Status"].(string); ok {
		st = strings.TrimPrefix(st, "minecraft:")
		if st != "full" && st != "postprocessed" && st != "fullchunk" && st != "spawn" && st != "heightmaps" {
			return nil // proto-chunk at the world's edge: not terrain yet
		}
	}
	secs, _ := lvl["sections"].([]any)
	if secs == nil {
		secs, _ = lvl["Sections"].([]any)
	}
	minSec := 1 << 30
	for _, e := range secs {
		m, _ := e.(map[string]any)
		if m == nil {
			continue
		}
		y := int(int8(nbtInt(m["Y"])))
		var pal []string
		var data []int64
		if bs, ok := m["block_states"].(map[string]any); ok {
			pal = paletteNames(bs["palette"])
			data, _ = bs["data"].([]int64)
		} else {
			pal = paletteNames(m["Palette"])
			data, _ = m["BlockStates"].([]int64)
		}
		if len(pal) == 0 {
			continue
		}
		b := max(4, bits.Len(uint(len(pal)-1)))
		c.sections[y] = &section{pal: pal, data: data, bits: b, spanning: spanning}
		minSec = min(minSec, y)
	}
	if hm, ok := lvl["Heightmaps"].(map[string]any); ok {
		c.height, _ = hm["MOTION_BLOCKING"].([]int64)
		if c.height == nil {
			c.height, _ = hm["WORLD_SURFACE"].([]int64)
		}
	}
	if dv >= 2844 { // 1.18+: the world starts below zero
		if yp, ok := lvl["yPos"]; ok {
			c.minY = int(nbtInt(yp)) * 16
		} else if minSec != 1<<30 {
			c.minY = minSec * 16
			if c.minY < -64 {
				c.minY = -64
			}
		}
	}
	if L := len(c.height); L > 0 {
		if spanning {
			c.hbits = L * 64 / 256 // e.g. 36 longs -> 9 bits, tightly packed
		} else {
			per := (256 + L - 1) / L // entries per long, never split across longs
			c.hbits = 64 / per
		}
		if c.hbits <= 0 || c.hbits > 32 {
			c.hbits = 9
		}
		c.hspan = spanning
	}
	return c
}

// readRegion yields the decoded NBT of every chunk stored in a region file.
func readRegion(path string, fn func(cx, cz int, root map[string]any)) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if len(b) < 8192 {
		return errors.New("region too small")
	}
	for i := 0; i < 1024; i++ {
		loc := binary.BigEndian.Uint32(b[i*4:])
		off, cnt := int(loc>>8)*4096, int(loc&0xff)*4096
		if off == 0 || cnt == 0 || off+5 > len(b) {
			continue
		}
		length := int(binary.BigEndian.Uint32(b[off:]))
		if length <= 1 || off+4+length > len(b) {
			continue
		}
		comp := b[off+4]
		payload := b[off+5 : off+4+length]
		var r io.Reader
		switch comp {
		case 1:
			gz, err := gzip.NewReader(bytes.NewReader(payload))
			if err != nil {
				continue
			}
			r = gz
		case 2:
			zr, err := zlib.NewReader(bytes.NewReader(payload))
			if err != nil {
				continue
			}
			r = zr
		case 3:
			r = bytes.NewReader(payload)
		default:
			continue // LZ4 (optional since 1.20.5) or external .mcc: skipped
		}
		nr := &nbtReader{r: r, keep: true}
		if nr.u8() != 10 {
			continue
		}
		nr.str()
		v := nr.payload(10, 0)
		if nr.err != nil {
			continue
		}
		if m, ok := v.(map[string]any); ok {
			fn(i%32, i/32, m)
		}
	}
	return nil
}

// ---------------------------------------------------------------- colours

var dyes = map[string]color.RGBA{
	"white": {233, 236, 236, 255}, "orange": {240, 118, 19, 255}, "magenta": {189, 68, 179, 255},
	"light_blue": {58, 175, 217, 255}, "yellow": {248, 197, 39, 255}, "lime": {112, 185, 25, 255},
	"pink": {237, 141, 172, 255}, "gray": {62, 68, 71, 255}, "light_gray": {142, 142, 134, 255},
	"cyan": {21, 137, 145, 255}, "purple": {121, 42, 172, 255}, "blue": {53, 57, 157, 255},
	"brown": {114, 71, 40, 255}, "green": {84, 109, 27, 255}, "red": {161, 39, 34, 255}, "black": {20, 21, 25, 255},
}

var blockColors = map[string]color.RGBA{
	"grass_block": {121, 168, 74, 255}, "dirt": {134, 96, 67, 255}, "coarse_dirt": {119, 85, 59, 255},
	"rooted_dirt": {144, 103, 76, 255}, "podzol": {91, 63, 24, 255}, "mycelium": {111, 99, 105, 255},
	"farmland": {143, 102, 70, 255}, "dirt_path": {148, 121, 65, 255}, "mud": {60, 57, 60, 255},
	"sand": {219, 207, 163, 255}, "red_sand": {190, 102, 33, 255}, "sandstone": {216, 203, 155, 255},
	"red_sandstone": {186, 99, 29, 255}, "gravel": {136, 126, 125, 255}, "clay": {160, 166, 179, 255},
	"stone": {125, 125, 125, 255}, "cobblestone": {122, 122, 122, 255}, "mossy_cobblestone": {110, 118, 94, 255},
	"andesite": {136, 136, 137, 255}, "diorite": {188, 188, 188, 255}, "granite": {149, 103, 85, 255},
	"deepslate": {80, 80, 82, 255}, "tuff": {108, 109, 102, 255}, "calcite": {223, 224, 220, 255},
	"dripstone_block": {134, 107, 92, 255}, "moss_block": {89, 109, 45, 255}, "bedrock": {85, 85, 85, 255},
	"snow": {249, 254, 254, 255}, "snow_block": {249, 254, 254, 255}, "powder_snow": {248, 253, 253, 255},
	"ice": {145, 183, 253, 255}, "packed_ice": {141, 180, 250, 255}, "blue_ice": {116, 167, 253, 255},
	"water": {63, 118, 228, 255}, "bubble_column": {63, 118, 228, 255}, "lava": {207, 92, 20, 255},
	"seagrass": {63, 118, 228, 255}, "tall_seagrass": {63, 118, 228, 255}, "kelp": {63, 118, 228, 255}, "kelp_plant": {63, 118, 228, 255},
	"oak_leaves": {59, 117, 33, 255}, "birch_leaves": {104, 139, 58, 255}, "spruce_leaves": {56, 92, 56, 255},
	"jungle_leaves": {48, 128, 26, 255}, "acacia_leaves": {82, 120, 30, 255}, "dark_oak_leaves": {44, 92, 22, 255},
	"mangrove_leaves": {68, 118, 34, 255}, "cherry_leaves": {238, 177, 202, 255}, "azalea_leaves": {95, 120, 45, 255},
	"flowering_azalea_leaves": {125, 120, 85, 255}, "pale_oak_leaves": {130, 138, 120, 255},
	"oak_log": {109, 85, 50, 255}, "spruce_log": {58, 37, 16, 255}, "birch_log": {216, 215, 210, 255},
	"jungle_log": {85, 67, 25, 255}, "acacia_log": {103, 96, 86, 255}, "dark_oak_log": {60, 46, 26, 255},
	"cherry_log": {54, 33, 44, 255}, "mangrove_log": {84, 66, 36, 255},
	"oak_planks": {162, 130, 78, 255}, "spruce_planks": {114, 84, 48, 255}, "birch_planks": {192, 175, 121, 255},
	"jungle_planks": {160, 115, 80, 255}, "acacia_planks": {168, 90, 50, 255}, "dark_oak_planks": {66, 43, 20, 255},
	"cherry_planks": {226, 178, 172, 255}, "mangrove_planks": {117, 54, 48, 255}, "bamboo_planks": {193, 173, 80, 255},
	"netherrack": {97, 38, 38, 255}, "nether_wart_block": {114, 2, 2, 255}, "warped_wart_block": {22, 119, 121, 255},
	"crimson_nylium": {130, 31, 31, 255}, "warped_nylium": {43, 114, 101, 255}, "soul_sand": {81, 62, 50, 255},
	"soul_soil": {75, 57, 46, 255}, "basalt": {73, 72, 77, 255}, "blackstone": {42, 36, 41, 255},
	"glowstone": {171, 131, 84, 255}, "magma_block": {142, 63, 31, 255}, "shroomlight": {240, 146, 70, 255},
	"end_stone": {219, 222, 158, 255}, "obsidian": {15, 10, 24, 255}, "purpur_block": {169, 125, 169, 255},
	"chorus_plant": {93, 57, 93, 255}, "chorus_flower": {151, 120, 151, 255},
	"hay_block": {166, 139, 12, 255}, "pumpkin": {198, 118, 24, 255}, "melon": {111, 145, 30, 255},
	"bricks": {150, 97, 83, 255}, "glass": {175, 213, 219, 255}, "quartz_block": {235, 229, 222, 255},
	"bamboo": {93, 144, 19, 255}, "cactus": {85, 127, 43, 255}, "sugar_cane": {148, 192, 101, 255},
	"lily_pad": {32, 128, 48, 255}, "terracotta": {152, 94, 67, 255}, "mangrove_roots": {74, 59, 38, 255},
	"muddy_mangrove_roots": {68, 58, 46, 255}, "sculk": {12, 30, 36, 255}, "amethyst_block": {133, 97, 191, 255},
	"copper_block": {192, 107, 79, 255}, "iron_block": {220, 220, 220, 255}, "gold_block": {246, 208, 61, 255},
	"diamond_block": {98, 237, 228, 255}, "emerald_block": {42, 203, 87, 255}, "pale_moss_block": {106, 112, 104, 255},
}

var colorCache sync.Map

func blockColor(name string) (color.RGBA, bool) {
	if v, ok := colorCache.Load(name); ok {
		c := v.(color.RGBA)
		return c, c.A > 0
	}
	c := guessColor(name)
	colorCache.Store(name, c)
	return c, c.A > 0
}

func guessColor(n string) color.RGBA {
	if c, ok := blockColors[n]; ok {
		return c
	}
	switch {
	case n == "air" || n == "cave_air" || n == "void_air" || n == "barrier" || n == "light" || n == "structure_void":
		return color.RGBA{}
	}
	for d, c := range dyes { // wool, concrete, terracotta, glass, carpet, beds …
		if strings.HasPrefix(n, d+"_") {
			if strings.HasSuffix(n, "_terracotta") {
				return color.RGBA{uint8(int(c.R)*6/10 + 50), uint8(int(c.G)*6/10 + 35), uint8(int(c.B)*6/10 + 30), 255}
			}
			return c
		}
	}
	for _, suf := range []string{"_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_pressure_plate", "_button", "_trapdoor", "_door"} {
		if strings.HasSuffix(n, suf) {
			base := strings.TrimSuffix(n, suf)
			for _, try := range []string{base, base + "s", base + "_planks", base + "_block"} {
				if c, ok := blockColors[try]; ok {
					return c
				}
			}
		}
	}
	switch {
	case strings.Contains(n, "leaves"):
		return color.RGBA{62, 112, 36, 255}
	case strings.HasSuffix(n, "_log") || strings.HasSuffix(n, "_wood") || strings.HasSuffix(n, "_stem") || strings.HasSuffix(n, "_hyphae"):
		return color.RGBA{102, 80, 48, 255}
	case strings.Contains(n, "planks"):
		return color.RGBA{162, 130, 78, 255}
	case strings.Contains(n, "deepslate"):
		return blockColors["deepslate"]
	case strings.HasSuffix(n, "_ore"):
		return blockColors["stone"]
	case strings.Contains(n, "copper"):
		return blockColors["copper_block"]
	case strings.Contains(n, "glass"):
		return blockColors["glass"]
	case strings.Contains(n, "sandstone"):
		return blockColors["sandstone"]
	case strings.Contains(n, "stone") || strings.Contains(n, "brick"):
		return color.RGBA{118, 118, 118, 255}
	case strings.Contains(n, "coral"):
		return color.RGBA{200, 90, 130, 255}
	case strings.Contains(n, "mushroom"):
		return color.RGBA{150, 110, 90, 255}
	}
	return color.RGBA{128, 128, 128, 255}
}

func shade(c color.RGBA, f float64) color.RGBA {
	cl := func(v float64) uint8 { return uint8(max(0, min(255, v))) }
	return color.RGBA{cl(float64(c.R) * f), cl(float64(c.G) * f), cl(float64(c.B) * f), 255}
}

func isWater(n string) bool {
	return n == "water" || n == "seagrass" || n == "tall_seagrass" || n == "kelp" || n == "kelp_plant" || n == "bubble_column"
}

// ---------------------------------------------------------------- rendering

// RenderRegion draws one region (512×512 blocks) top-down, one pixel per block.
func RenderRegion(worldDir, dim string, rx, rz int) (*image.RGBA, error) {
	rel, ok := dimDirs[dim]
	if !ok {
		return nil, fmt.Errorf("未知维度 %s", dim)
	}
	img := image.NewRGBA(image.Rect(0, 0, 512, 512))
	heights := make([]int, 512*512)
	for i := range heights {
		heights[i] = -1 << 20
	}
	cols := make([]color.RGBA, 512*512)
	path := filepath.Join(worldDir, rel, fmt.Sprintf("r.%d.%d.mca", rx, rz))
	err := readRegion(path, func(cx, cz int, root map[string]any) {
		ch := parseChunk(root)
		if ch == nil {
			return
		}
		for z := 0; z < 16; z++ {
			for x := 0; x < 16; x++ {
				var y int
				var name string
				found := false
				if dim == "nether" {
					y, name, found = netherTop(ch, x, z)
				} else if ty, ok := ch.top(x, z); ok {
					// the heightmap can point at a block that was removed later: walk down a little
					for yy := ty; yy > ty-8; yy-- {
						if n := ch.block(x, yy, z); n != "air" && n != "cave_air" {
							y, name, found = yy, n, true
							break
						}
					}
				}
				if !found {
					continue
				}
				c, ok := blockColor(name)
				if !ok {
					continue
				}
				if isWater(name) { // deeper water is darker
					depth := 0
					for yy := y - 1; yy > y-24; yy-- {
						if !isWater(ch.block(x, yy, z)) && !strings.HasSuffix(ch.block(x, yy, z), "_leaves") {
							break
						}
						depth++
					}
					c = shade(c, 1.08-min(.5, float64(depth)*.035))
				}
				px, pz := cx*16+x, cz*16+z
				heights[pz*512+px] = y
				cols[pz*512+px] = c
			}
		}
	})
	if err != nil {
		return nil, err
	}
	// relief: light from the north-west, like the in-game map
	for pz := 0; pz < 512; pz++ {
		for px := 0; px < 512; px++ {
			i := pz*512 + px
			c := cols[i]
			if c.A == 0 {
				continue
			}
			f := 1.0
			if pz > 0 && heights[i-512] > -1<<19 && !isWaterColor(c) {
				d := heights[i] - heights[i-512]
				switch {
				case d > 0:
					f = 1.12
				case d < 0:
					f = .84
				}
			}
			img.SetRGBA(px, pz, shade(c, f))
		}
	}
	return img, nil
}

func isWaterColor(c color.RGBA) bool { return c.B > c.R+60 && c.B > c.G+40 }

// netherTop finds the first floor below the bedrock roof (the nether has no sky).
func netherTop(ch *chunk, x, z int) (int, string, bool) {
	air := false
	for y := 118; y > 0; y-- {
		n := ch.block(x, y, z)
		if n == "air" || n == "cave_air" {
			air = true
			continue
		}
		if air && n != "fire" && n != "soul_fire" && !strings.HasSuffix(n, "_roots") && !strings.HasSuffix(n, "_fungus") {
			return y, n, true
		}
	}
	return 0, "", false
}

// RegionTile renders (or returns the cached) PNG for a region.
var tileLocks sync.Map

func RegionTile(cacheDir, worldDir, dim string, rx, rz int) ([]byte, error) {
	rel, ok := dimDirs[dim]
	if !ok {
		return nil, errors.New("bad dim")
	}
	src := filepath.Join(worldDir, rel, fmt.Sprintf("r.%d.%d.mca", rx, rz))
	sst, err := os.Stat(src)
	if err != nil {
		return nil, err
	}
	dst := filepath.Join(cacheDir, dim, fmt.Sprintf("r.%d.%d.png", rx, rz))
	if dt, err := os.Stat(dst); err == nil && !dt.ModTime().Before(sst.ModTime()) {
		return os.ReadFile(dst)
	}
	mu, _ := tileLocks.LoadOrStore(dst, &sync.Mutex{})
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()
	if dt, err := os.Stat(dst); err == nil && !dt.ModTime().Before(sst.ModTime()) {
		return os.ReadFile(dst)
	}
	img, err := RenderRegion(worldDir, dim, rx, rz)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := (&png.Encoder{CompressionLevel: png.BestSpeed}).Encode(&buf, img); err != nil {
		return nil, err
	}
	_ = os.MkdirAll(filepath.Dir(dst), 0o755)
	_ = os.WriteFile(dst, buf.Bytes(), 0o644)
	return buf.Bytes(), nil
}
