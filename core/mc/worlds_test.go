package mc

import (
	"archive/zip"
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

type nbtW struct{ b bytes.Buffer }

func (w *nbtW) str(s string) {
	binary.Write(&w.b, binary.BigEndian, uint16(len(s)))
	w.b.WriteString(s)
}
func (w *nbtW) tag(t byte, name string) { w.b.WriteByte(t); w.str(name) }

func writeLevel(t *testing.T, path, name string, mode int32, hardcore byte, last int64) {
	w := &nbtW{}
	w.tag(10, "")
	w.tag(10, "Data")
	w.tag(8, "LevelName")
	w.str(name)
	w.tag(3, "GameType")
	binary.Write(&w.b, binary.BigEndian, mode)
	w.tag(1, "hardcore")
	w.b.WriteByte(hardcore)
	w.tag(4, "LastPlayed")
	binary.Write(&w.b, binary.BigEndian, last)
	w.tag(4, "DayTime")
	binary.Write(&w.b, binary.BigEndian, int64(24000*42+100))
	w.tag(7, "Blob")
	binary.Write(&w.b, binary.BigEndian, int32(3))
	w.b.Write([]byte{1, 2, 3})
	w.tag(9, "Players")
	w.b.WriteByte(8)
	binary.Write(&w.b, binary.BigEndian, int32(2))
	w.str("a")
	w.str("b")
	w.tag(10, "Version")
	w.tag(8, "Name")
	w.str("1.21.1")
	w.b.WriteByte(0)
	w.tag(10, "WorldGenSettings")
	w.tag(4, "seed")
	binary.Write(&w.b, binary.BigEndian, int64(-4172144997902289642))
	w.b.WriteByte(0)
	w.b.WriteByte(0) // end Data
	w.b.WriteByte(0) // end root
	os.MkdirAll(filepath.Dir(path), 0o755)
	f, _ := os.Create(path)
	gz := gzip.NewWriter(f)
	gz.Write(w.b.Bytes())
	gz.Close()
	f.Close()
}

func TestWorlds(t *testing.T) {
	run := t.TempDir()
	writeLevel(t, filepath.Join(run, "saves", "New World", "level.dat"), "樱花岛", 0, 1, 1760000000000)
	writeLevel(t, filepath.Join(run, "saves", "creative", "level.dat"), "建筑档", 1, 0, 1700000000000)
	os.WriteFile(filepath.Join(run, "saves", "New World", "icon.png"), []byte("png"), 0o644)
	os.MkdirAll(filepath.Join(run, "saves", "not-a-world"), 0o755)
	ws := ListWorlds(run)
	if len(ws) != 2 {
		t.Fatal(ws)
	}
	w := ws[0]
	if w.Name != "樱花岛" || w.Mode != "生存" || !w.Hardcore || !w.HasIcon || w.Version != "1.21.1" || w.Day != 42 ||
		w.LastPlayed != 1760000000 || w.Seed != "-4172144997902289642" {
		t.Fatalf("%+v", w)
	}
	if ws[1].Mode != "创造" {
		t.Fatal(ws[1])
	}
	zp, err := BackupWorld(t.Context(), run, "New World")
	if err != nil {
		t.Fatal(err)
	}
	zr, err := zip.OpenReader(zp)
	if err != nil || len(zr.File) != 2 {
		t.Fatal("backup", err)
	}
	zr.Close()
	if DeleteWorld(run, "../x") == nil || DeleteWorld(run, "") == nil {
		t.Fatal("escape")
	}
	if err := DeleteWorld(run, "creative"); err != nil || len(ListWorlds(run)) != 1 {
		t.Fatal("delete")
	}
}

func TestOptimizeHelpers(t *testing.T) {
	d := t.TempDir()
	chineseByDefault(d, "1.8.9")
	if b, _ := os.ReadFile(filepath.Join(d, "options.txt")); string(b) != "lang:zh_CN\n" {
		t.Fatal(string(b))
	}
	os.WriteFile(filepath.Join(d, "options.txt"), []byte("lang:en_us\n"), 0o644)
	chineseByDefault(d, "1.21.1")
	if b, _ := os.ReadFile(filepath.Join(d, "options.txt")); string(b) != "lang:en_us\n" {
		t.Fatal("overwrote options")
	}
	d2 := t.TempDir()
	chineseByDefault(d2, "1.21.1")
	if b, _ := os.ReadFile(filepath.Join(d2, "options.txt")); string(b) != "lang:zh_cn\n" {
		t.Fatal(string(b))
	}
	os.MkdirAll(filepath.Join(d, "mods"), 0o755)
	os.WriteFile(filepath.Join(d, "mods", "ferritecore-6.0.1-fabric.jar"), []byte("x"), 0o644)
	if !hasModLike(d, "ferrite-core") || hasModLike(d, "lithium") {
		t.Fatal("hasModLike")
	}
}

func TestForeignInstance(t *testing.T) {
	dir := setup(t)
	// an HMCL/PCL-style merged Fabric instance: no inheritsFrom, extra fields we must keep
	id := "1.21.11-Fabric 0.19.3"
	os.MkdirAll(VersionDir(dir, id), 0o755)
	js := `{"id":"1.21.11-Fabric 0.19.3","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","clientVersion":"1.21.11",
	 "patches":[{"id":"game","version":"1.21.11","priority":0},{"id":"fabric","version":"0.19.3"}],"libraries":[{"name":"net.fabricmc:fabric-loader:0.19.3"}],"hmclExtra":{"a":1}}`
	os.WriteFile(VersionJSON(dir, id), []byte(js), 0o644)
	if g := GameVersion(dir, id); g != "1.21.11" {
		t.Fatal(g)
	}
	if l := InstanceLoader(dir, id); l != "fabric" {
		t.Fatal(l)
	}
	stampMeta(dir, id, func(m *InstanceMeta) { m.PlayTime += 60 })
	b, _ := os.ReadFile(VersionJSON(dir, id))
	for _, keep := range []string{"hmclExtra", "patches", `"priority": 0`, "clientVersion", "eririlily"} {
		if !bytes.Contains(b, []byte(keep)) {
			t.Fatalf("lost %s:\n%s", keep, b)
		}
	}
	if l := InstanceLoader(dir, id); l != "fabric" {
		t.Fatal("loader after stamp", l)
	}
	// plain id fallbacks
	for idv, want := range map[string]string{"1.20.1-forge-47.3.0": "1.20.1", "Fabulously Optimized 1.21.4": "1.21.4", "24w14a": "24w14a"} {
		if g := GameVersion(t.TempDir(), idv); g != want {
			t.Errorf("%s -> %s", idv, g)
		}
	}
}

func TestServerArgs(t *testing.T) {
	if a := serverArgs("1.21.1", "play.eririlily.cn"); len(a) != 2 || a[0] != "--quickPlayMultiplayer" {
		t.Fatal(a)
	}
	if a := serverArgs("1.12.2", "mc.example.com:25566"); len(a) != 4 || a[2] != "--port" || a[3] != "25566" {
		t.Fatal(a)
	}
	if a := serverArgs("1.21.1", "bad host; rm -rf"); a != nil {
		t.Fatal("unsafe address accepted")
	}
}
