package mc

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ulikunitz/xz"
)

func tarXZ(t *testing.T, files map[string]string) []byte {
	var buf bytes.Buffer
	xw, err := xz.NewWriter(&buf)
	if err != nil {
		t.Fatal(err)
	}
	tw := tar.NewWriter(xw)
	for name, body := range files {
		_ = tw.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg})
		_, _ = tw.Write([]byte(body))
	}
	_ = tw.WriteHeader(&tar.Header{Name: "jre/bin/link", Linkname: "../lib/server/libjvm.so", Typeflag: tar.TypeSymlink})
	_ = tw.WriteHeader(&tar.Header{Name: "jre/evil", Linkname: "/etc/passwd", Typeflag: tar.TypeSymlink})
	tw.Close()
	xw.Close()
	return buf.Bytes()
}

// fakeAndroid prepares an APK with a bundled JRE 17 and LWJGL jars, and a native dir with renderers.
func fakeAndroid(t *testing.T) (dataDir string) {
	t.Helper()
	old := Android
	Android = true
	t.Cleanup(func() { Android = old; AEnv = AndroidEnv{}; resetJavaCache() })
	root := t.TempDir()
	apk := filepath.Join(root, "base.apk")
	f, _ := os.Create(apk)
	z := zip.NewWriter(f)
	w, _ := z.Create("assets/runtime/jre17.tar.xz")
	_, _ = w.Write(tarXZ(t, map[string]string{
		"jre/release":              `JAVA_VERSION="17.0.12"` + "\n" + `IMPLEMENTOR="Test"`,
		"jre/lib/server/libjvm.so": "elf",
		"jre/lib/libjava.so":       "elf",
	}))
	for _, j := range []string{"lwjgl.jar", "lwjgl-glfw.jar", "lwjgl-opengl.jar"} {
		w, _ = z.Create("assets/lwjgl3/" + j)
		_, _ = w.Write([]byte("PK"))
	}
	z.Close()
	f.Close()
	native := filepath.Join(root, "lib")
	_ = os.MkdirAll(native, 0o755)
	for _, l := range []string{"libelbridge.so", "libgl4es.so", "libmobileglues.so", "libopenal.so"} {
		_ = os.WriteFile(filepath.Join(native, l), []byte("elf"), 0o644)
	}
	AEnv = AndroidEnv{NativeDir: native, APK: apk, CacheDir: filepath.Join(root, "cache"), ABI: "arm64-v8a",
		Device: Device{RAMMB: 12000, MaxFreqMHz: 3300, GLRenderer: "Adreno (TM) 750", Refresh: 120, ScreenW: 3200, ScreenH: 1440, Brand: "Test", Model: "Phone"}}
	resetJavaCache()
	return filepath.Join(root, "data")
}

func TestAndroidRuntimeInstall(t *testing.T) {
	data := fakeAndroid(t)
	if got := BundledRuntimes(); len(got) != 1 || got[0] != 17 {
		t.Fatalf("bundled = %v", got)
	}
	j, err := InstallRuntime(context.Background(), data, "java-runtime-gamma", 4, nil)
	if err != nil {
		t.Fatal(err)
	}
	if j.Major != 17 || !strings.HasSuffix(j.Path, "libjvm.so") {
		t.Fatalf("java = %+v", j)
	}
	if _, err := os.Lstat(filepath.Join(j.Home, "evil")); err == nil {
		t.Fatal("absolute symlink must be dropped")
	}
	all := DetectJava("", data, true)
	if len(all) != 1 || PickJava(all, 8) == nil || PickJava(all, 21) != nil {
		t.Fatalf("detect/pick: %+v", all)
	}
	if err := RemoveRuntime(data, "/etc"); err == nil {
		t.Fatal("must refuse to delete outside the runtime folder")
	}
}

func TestAndroidSpec(t *testing.T) {
	data := fakeAndroid(t)
	dir := t.TempDir()
	js := strings.Replace(vanillaJSON, `{"name":"org.ow2.asm:asm:9.3"}`,
		`{"name":"org.ow2.asm:asm:9.3"},{"name":"org.lwjgl:lwjgl:3.3.3","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar","sha1":"d","size":1,"url":"https://libraries.minecraft.net/z.jar"}}}`, 1)
	_ = os.MkdirAll(VersionDir(dir, "1.21.1"), 0o755)
	_ = os.WriteFile(VersionJSON(dir, "1.21.1"), []byte(js), 0o644)
	_ = os.WriteFile(filepath.Join(VersionDir(dir, "1.21.1"), "1.21.1.jar"), []byte("jar!"), 0o644)
	j, err := InstallRuntime(context.Background(), data, "java-runtime-gamma", 4, nil)
	if err != nil {
		t.Fatal(err)
	}
	cfg := DefaultConfig(dir)
	cfg.GameDir = dir
	acc, _ := OfflineAccount("Steve")
	opts := LaunchOptions{GameDir: dir, ID: "1.21.1", Java: j, Account: acc, Isolation: true}
	ga, err := BuildArgs(opts)
	if err != nil {
		t.Fatal(err)
	}
	var started string
	AndroidStart = func(p string) { started = p }
	defer func() { AndroidStart = nil }()
	sp, err := buildAndroidSpec(cfg, data, LaunchRequest{Config: cfg, DataDir: data, ID: "1.21.1", Account: acc}, ga, j)
	if err != nil {
		t.Fatal(err)
	}
	_ = started
	if sp.Renderer.ID != "mobileglues" {
		t.Errorf("renderer = %s", sp.Renderer.ID)
	}
	if !strings.Contains(sp.Classpath[0], "lwjgl3") {
		t.Errorf("our LWJGL must come first: %v", sp.Classpath)
	}
	for _, p := range sp.Classpath {
		if strings.Contains(p, "/org/lwjgl/") {
			t.Errorf("desktop LWJGL left on the class path: %s", p)
		}
	}
	joined := strings.Join(sp.JVMArgs, " ")
	for _, want := range []string{"-Dorg.lwjgl.glfw.libname=", "libelbridge.so", "-Dorg.lwjgl.opengl.libname=", "-Xmx"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %s in %s", want, joined)
		}
	}
	if strings.Contains(joined, " -cp ") || strings.Contains(joined, "natives-linux") {
		t.Errorf("class path / desktop natives must not stay in JVM args: %s", joined)
	}
	if sp.MainClass != "net.minecraft.client.main.Main" || len(sp.GameArgs) == 0 || sp.GameArgs[0] != "--username" {
		t.Errorf("main/game args: %s %v", sp.MainClass, sp.GameArgs)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(sp.LogFile), "spec.json")); err != nil {
		t.Error("spec.json not written")
	}
}

func TestPlanOptimize(t *testing.T) {
	flag := Device{RAMMB: 12000, MaxFreqMHz: 3300, GLRenderer: "Adreno (TM) 750", Refresh: 120, ScreenW: 3200, ScreenH: 1440}
	low := Device{RAMMB: 4000, MaxFreqMHz: 2000, GLRenderer: "Mali-G52", Refresh: 60, ScreenW: 1600, ScreenH: 720}
	p := PlanOptimize(flag, "balanced", "1.21.1")
	if p.Tier != 3 || p.FpsLimit != 120 || p.RenderScale != 75 {
		t.Errorf("flagship plan: %+v", p)
	}
	q := PlanOptimize(low, "extreme", "1.21.1")
	if q.Tier != 1 || q.FpsLimit != 60 || q.RenderScale > 60 || q.Options["renderDistance"] != "4" {
		t.Errorf("low plan: %+v", q)
	}
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "options.txt"), []byte("lang:zh_cn\nrenderDistance:16\n"), 0o644)
	if err := ApplyOptions(dir, map[string]string{"renderDistance": "8", "maxFps": "90"}); err != nil {
		t.Fatal(err)
	}
	o := ReadOptions(dir, nil)
	if o["renderDistance"] != "8" || o["maxFps"] != "90" || o["lang"] != "zh_cn" {
		t.Errorf("options: %v", o)
	}
}

func TestGameCmp(t *testing.T) {
	if gameCmp("1.16.5", "1.17") >= 0 || gameCmp("1.20.1", "1.17") <= 0 || gameCmp("26.1", "1.21.4") <= 0 || gameCmp("1.17", "1.17") != 0 {
		t.Error("gameCmp")
	}
}
