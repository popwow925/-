package app

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"eririlily/mc"
)

// fixture builds a realistic .minecraft: vanilla versions with ~70 libraries,
// a 4,200-object asset index with every file present, and Fabric instances
// inheriting from them, each with a mods folder.
func fixture(tb testing.TB, root string, nVanilla, nFabric, mods, assets int) string {
	tb.Helper()
	gd := filepath.Join(root, ".minecraft")
	write := func(p string, b []byte) {
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		if err := os.WriteFile(p, b, 0o644); err != nil {
			tb.Fatal(err)
		}
	}
	sum := func(b []byte) string { h := sha1.Sum(b); return hex.EncodeToString(h[:]) }
	// assets
	objs := map[string]any{}
	for i := 0; i < assets; i++ {
		b := []byte(fmt.Sprintf("asset-%d", i))
		h := sum(b)
		write(filepath.Join(gd, "assets", "objects", h[:2], h), b)
		objs[fmt.Sprintf("minecraft/sounds/x/%d.ogg", i)] = map[string]any{"hash": h, "size": len(b)}
	}
	ib, _ := json.Marshal(map[string]any{"objects": objs})
	write(filepath.Join(gd, "assets", "indexes", "17.json"), ib)
	for v := 0; v < nVanilla; v++ {
		id := fmt.Sprintf("1.21.%d", v)
		var libs []any
		for i := 0; i < 70; i++ {
			b := []byte(fmt.Sprintf("lib-%d-%d", v, i))
			p := fmt.Sprintf("com/example/lib%d/%d.0/lib%d-%d.0.jar", i, v, i, v)
			write(filepath.Join(gd, "libraries", filepath.FromSlash(p)), b)
			libs = append(libs, map[string]any{"name": fmt.Sprintf("com.example:lib%d:%d.0", i, v),
				"downloads": map[string]any{"artifact": map[string]any{"path": p, "sha1": sum(b), "size": len(b),
					"url": "https://libraries.minecraft.net/" + p}},
				"rules": []any{map[string]any{"action": "allow"}}})
		}
		cb := []byte("client-" + id)
		write(filepath.Join(gd, "versions", id, id+".jar"), cb)
		var game []any
		for i := 0; i < 40; i++ {
			game = append(game, fmt.Sprintf("--opt%d", i), fmt.Sprintf("${val%d}", i))
		}
		vj := map[string]any{"id": id, "type": "release", "mainClass": "net.minecraft.client.main.Main",
			"assets": "17", "assetIndex": map[string]any{"id": "17", "sha1": sum(ib), "size": len(ib), "url": "https://piston-meta.mojang.com/x/17.json"},
			"downloads":   map[string]any{"client": map[string]any{"sha1": sum(cb), "size": len(cb), "url": "https://piston-data.mojang.com/x/client.jar"}},
			"javaVersion": map[string]any{"component": "java-runtime-delta", "majorVersion": 21},
			"libraries":   libs, "arguments": map[string]any{"game": game, "jvm": []any{"-cp", "${classpath}"}}}
		b, _ := json.MarshalIndent(vj, "", "  ")
		write(filepath.Join(gd, "versions", id, id+".json"), b)
	}
	for f := 0; f < nFabric; f++ {
		parent := fmt.Sprintf("1.21.%d", f%nVanilla)
		id := fmt.Sprintf("%s-Fabric 0.16.%d", parent, f)
		var libs []any
		for i := 0; i < 8; i++ {
			b := []byte(fmt.Sprintf("fab-%d-%d", f, i))
			p := fmt.Sprintf("net/fabricmc/x%d/%d/x%d-%d.jar", i, f, i, f)
			write(filepath.Join(gd, "libraries", filepath.FromSlash(p)), b)
			libs = append(libs, map[string]any{"name": fmt.Sprintf("net.fabricmc:x%d:%d", i, f), "url": "https://maven.fabricmc.net/", "sha1": sum(b), "size": len(b)})
		}
		libs = append(libs, map[string]any{"name": "net.fabricmc:fabric-loader:0.16.0", "url": "https://maven.fabricmc.net/"})
		write(filepath.Join(gd, "libraries", "net/fabricmc/fabric-loader/0.16.0/fabric-loader-0.16.0.jar"), []byte("loader"))
		vj := map[string]any{"id": id, "inheritsFrom": parent, "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
			"libraries": libs, "eririlily": map[string]any{"name": id, "game": parent, "loader": "fabric", "created": time.Now().Unix() - int64(f)}}
		b, _ := json.MarshalIndent(vj, "", "  ")
		write(filepath.Join(gd, "versions", id, id+".json"), b)
		for m := 0; m < mods; m++ {
			write(filepath.Join(gd, "mods", fmt.Sprintf("mod-%d-%d.jar", f, m)), []byte("m"))
		}
	}
	return gd
}

func newBenchApp(tb testing.TB) (*App, string) {
	root := tb.TempDir()
	gd := fixture(tb, root, 4, 12, 150, 4200)
	a := &App{DataDir: filepath.Join(root, "data"), store: mc.OpenStore(filepath.Join(root, "data"), root),
		accounts: mc.OpenAccounts(filepath.Join(root, "data")), subs: map[chan []byte]bool{}}
	a.store.Update(func(c *mc.Config) { c.GameDir = gd; c.Isolation = false })
	return a, gd
}

func BenchmarkState(b *testing.B) {
	a, _ := newBenchApp(b)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = a.state()
	}
}

func BenchmarkCompleteWarm(b *testing.B) {
	_, gd := newBenchApp(b)
	id := "1.21.0-Fabric 0.16.0"
	if err := mc.Complete(context.Background(), gd, id, 16, false, nil); err != nil {
		b.Fatal(err)
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if err := mc.Complete(context.Background(), gd, id, 16, false, nil); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkEmitLogs(b *testing.B) {
	a, _ := newBenchApp(b)
	c := make(chan []byte, 512)
	a.subs[c] = true
	var got int
	var wg sync.WaitGroup
	wg.Add(1)
	stop := make(chan struct{})
	go func() {
		defer wg.Done()
		for {
			select {
			case <-c:
				got++
			case <-stop:
				return
			}
		}
	}()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		a.emit("log", "[12:00:00] [Render thread/INFO]: Loading mod example-mod 1.2.3")
	}
	b.StopTimer()
	close(stop)
	wg.Wait()
}

// TestLogBurst checks that a startup burst of log lines is not dropped.
func TestLogBurst(t *testing.T) {
	a, _ := newBenchApp(t)
	c := make(chan []byte, 512)
	a.subs[c] = true
	const n = 5000
	recv := 0
	done := make(chan struct{})
	go func() {
		for b := range c {
			var ev struct {
				Type string          `json:"type"`
				Data json.RawMessage `json:"data"`
			}
			_ = json.Unmarshal(b, &ev)
			if ev.Type == "log" {
				recv++
			} else if ev.Type == "logs" {
				var ls []string
				_ = json.Unmarshal(ev.Data, &ls)
				recv += len(ls)
			}
			time.Sleep(20 * time.Microsecond) // a busy UI thread
		}
		close(done)
	}()
	for i := 0; i < n; i++ {
		a.logLine(fmt.Sprintf("line %d", i))
	}
	a.flushLogs()
	time.Sleep(300 * time.Millisecond)
	a.mu.Lock()
	delete(a.subs, c)
	a.mu.Unlock()
	close(c)
	<-done
	t.Logf("received %d / %d log lines", recv, n)
	if recv != n {
		t.Fatalf("dropped %d log lines", n-recv)
	}
}
