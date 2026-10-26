package mc

import (
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const vanillaJSON = `{
 "id":"1.21.1","type":"release","mainClass":"net.minecraft.client.main.Main",
 "javaVersion":{"component":"java-runtime-delta","majorVersion":21},
 "assetIndex":{"id":"17","sha1":"x","size":1,"totalSize":1,"url":"https://piston-meta.mojang.com/v1/packages/x/17.json"},
 "assets":"17",
 "downloads":{"client":{"sha1":"abc","size":4,"url":"https://piston-data.mojang.com/v1/objects/abc/client.jar"}},
 "arguments":{
  "game":["--username","${auth_player_name}","--version","${version_name}","--gameDir","${game_directory}",
          "--assetsDir","${assets_root}","--assetIndex","${assets_index_name}","--uuid","${auth_uuid}",
          "--accessToken","${auth_access_token}","--userType","${user_type}","--versionType","${version_type}",
          {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
          {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${resolution_width}","--height","${resolution_height}"]}],
  "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
         {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=MojangTricksIntelFixesHeapDumpPath.exe_minecraft.exe.heapdump"},
         "-Djava.library.path=${natives_directory}","-Dminecraft.launcher.brand=${launcher_name}","-cp","${classpath}"]},
 "libraries":[
  {"name":"com.mojang:brigadier:1.3.10","downloads":{"artifact":{"path":"com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar","sha1":"a","size":1,"url":"https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"}}},
  {"name":"org.lwjgl:lwjgl:3.3.3:natives-windows","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar","sha1":"b","size":1,"url":"https://libraries.minecraft.net/x.jar"}},"rules":[{"action":"allow","os":{"name":"windows"}}]},
  {"name":"org.lwjgl:lwjgl:3.3.3:natives-linux","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar","sha1":"c","size":1,"url":"https://libraries.minecraft.net/y.jar"}},"rules":[{"action":"allow","os":{"name":"linux"}}]},
  {"name":"org.ow2.asm:asm:9.3"}
 ]}`

const fabricJSON = `{"id":"fabric-loader-0.16.5-1.21.1","inheritsFrom":"1.21.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
 "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
 "libraries":[{"name":"org.ow2.asm:asm:9.7.1","url":"https://maven.fabricmc.net/","sha1":"f"},
              {"name":"net.fabricmc:fabric-loader:0.16.5","url":"https://maven.fabricmc.net/"}]}`

func setup(t *testing.T) string {
	dir := t.TempDir()
	must := func(err error) {
		if err != nil {
			t.Fatal(err)
		}
	}
	must(os.MkdirAll(VersionDir(dir, "1.21.1"), 0o755))
	must(os.WriteFile(VersionJSON(dir, "1.21.1"), []byte(vanillaJSON), 0o644))
	must(os.WriteFile(filepath.Join(VersionDir(dir, "1.21.1"), "1.21.1.jar"), []byte("jar!"), 0o644))
	var v Version
	must(readJSON(VersionJSON(dir, "1.21.1"), &v))
	return dir
}

func TestOfflineUUID(t *testing.T) {
	a, err := OfflineAccount("Notch")
	if err != nil {
		t.Fatal(err)
	}
	// well-known offline UUID for "Notch"
	if a.ID != "b50ad385829d3141a2167e7d7539ba7f" {
		t.Fatalf("got %s", a.ID)
	}
	if _, err := OfflineAccount("樱梨"); err == nil {
		t.Fatal("non-ascii name accepted")
	}
}

func TestMaven(t *testing.T) {
	if p := MavenPath("org.lwjgl:lwjgl:3.3.3:natives-windows"); p != "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar" {
		t.Fatal(p)
	}
	if p := MavenPath("a.b:c:1@zip"); p != "a/b/c/1/c-1.zip" {
		t.Fatal(p)
	}
}

func TestLaunchArgsFabric(t *testing.T) {
	dir := setup(t)
	var fv Version
	if err := jsonUnmarshal([]byte(fabricJSON), &fv); err != nil {
		t.Fatal(err)
	}
	fv.ID = "樱花生存"
	fv.EL = &InstanceMeta{Name: "樱花生存", Game: "1.21.1", Loader: "fabric"}
	os.MkdirAll(VersionDir(dir, fv.ID), 0o755)
	if err := SaveVersion(dir, &fv); err != nil {
		t.Fatal(err)
	}
	acc, _ := OfflineAccount("D0cK")
	cmd, runDir, err := BuildCommand(LaunchOptions{GameDir: dir, ID: "樱花生存", Java: &Java{Path: "/usr/bin/java", Major: 21},
		Account: acc, MemoryMB: 4096, Isolation: true, Width: 1280, Height: 720})
	if err != nil {
		t.Fatal(err)
	}
	args := strings.Join(cmd.Args, " ")
	for _, want := range []string{"-Xmx4096m", "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username D0cK",
		"--version 樱花生存", "--assetIndex 17", "--userType legacy", "--accessToken 0", "--width 1280",
		"asm-9.7.1.jar", "fabric-loader-0.16.5.jar", "brigadier-1.3.10.jar", "1.21.1.jar"} {
		if !strings.Contains(args, want) {
			t.Errorf("missing %q in\n%s", want, args)
		}
	}
	if strings.Contains(args, "asm-9.3.jar") {
		t.Error("parent asm should be overridden by fabric's")
	}
	if strings.Contains(args, "--demo") {
		t.Error("demo flag leaked")
	}
	if strings.Count(args, "--width") != 1 {
		t.Error("width duplicated")
	}
	if runDir != VersionDir(dir, "樱花生存") {
		t.Error("isolation dir", runDir)
	}
	if strings.Contains(args, "${") {
		t.Error("unreplaced placeholder", args)
	}
	// main class must come after all JVM args, -cp value must be intact
	mi := indexOf(cmd.Args, "net.fabricmc.loader.impl.launch.knot.KnotClient")
	ci := indexOf(cmd.Args, "-cp")
	if ci < 0 || ci > mi || !strings.Contains(cmd.Args[ci+1], "brigadier") {
		t.Error("classpath order wrong")
	}
}

func TestVanillaChildAndFiles(t *testing.T) {
	dir := setup(t)
	v := &Version{ID: "生存档", InheritsFrom: "1.21.1", EL: &InstanceMeta{Name: "生存档"}}
	os.MkdirAll(VersionDir(dir, v.ID), 0o755)
	SaveVersion(dir, v)
	r, err := Resolve(dir, "生存档")
	if err != nil || r.Jar != "1.21.1" || r.MainClass != "net.minecraft.client.main.Main" {
		t.Fatal(err, r.Jar)
	}
	files, cp, natives := libraryFiles(dir, r)
	if len(cp) != 3 { // brigadier, natives(linux in test), asm
		t.Errorf("cp %d %v", len(cp), cp)
	}
	if len(natives) != 1 || len(files) != 3 {
		t.Errorf("natives %d files %d", len(natives), len(files))
	}
	if RequiredJava(dir, "生存档") != 21 {
		t.Error("java")
	}
}

func TestPickJava(t *testing.T) {
	all := []*Java{{Major: 25, Bits64: true}, {Major: 21, Bits64: true}, {Major: 17, Bits64: true}, {Major: 8, Bits64: true}}
	if PickJava(all, 21).Major != 21 || PickJava(all, 8).Major != 8 || PickJava(all, 16).Major != 17 {
		t.Fatal("pick")
	}
	if PickJava([]*Java{{Major: 8, Bits64: true}}, 17) != nil {
		t.Fatal("8 for 17")
	}
}

func TestDiagnose(t *testing.T) {
	cases := map[string]string{
		"Exception in thread \"main\" java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 52.0": "Java 版本太旧|21",
		"Error occurred during initialization of VM\nCould not reserve enough space for 8388608KB object heap":                               "内存设置过大|auto",
		" - Mod 'Sodium Extra' (sodium-extra) 0.5.4 requires any version of sodium, which is missing!":                                       "缺少前置模组|sodium",
		" - Mod 'Iris' (iris) 1.7.0 requires version 0.5.8 or later of mod 'Sodium' (sodium), but only the wrong version is present: 0.5.3!": "缺少前置模组|sodium",
		"java.util.zip.ZipException: invalid LOC header (bad signature)":                                                                     "游戏文件损坏|",
		"[LWJGL] GLFW_PLATFORM_ERROR error\nGLFW error 65542: WGL: The driver does not appear to support OpenGL":                             "显卡驱动不支持 OpenGL|",
	}
	for log, want := range cases {
		d := Diagnose(t.TempDir(), time.Now(), strings.Split(log, "\n"), 1)
		got := d.Title + "|" + d.FixArg
		if got != want {
			t.Errorf("%q\n got %q want %q (%s)", log[:40], got, want, d.Detail)
		}
	}
	if d := Diagnose(t.TempDir(), time.Now(), nil, -1073741819); !strings.Contains(d.Title, "访问冲突") {
		t.Error(d.Title)
	}
}

func TestMrpackOffline(t *testing.T) {
	dir := setup(t)
	// a vanilla-only pack: no loader, no remote files, overrides + a path-escape attempt
	p := filepath.Join(t.TempDir(), "pack.mrpack")
	f, _ := os.Create(p)
	zw := zip.NewWriter(f)
	w, _ := zw.Create("modrinth.index.json")
	w.Write([]byte(`{"formatVersion":1,"game":"minecraft","name":"樱之谷","versionId":"1","dependencies":{"minecraft":"1.21.1"},
	 "files":[{"path":"../../evil.jar","hashes":{"sha1":"x"},"downloads":["https://cdn.modrinth.com/x"],"fileSize":1}]}`))
	w, _ = zw.Create("overrides/config/sakura.toml")
	w.Write([]byte("petals = true"))
	w, _ = zw.Create("overrides/../../escape.txt")
	w.Write([]byte("no"))
	zw.Close()
	f.Close()
	id, err := InstallMrpack(t.Context(), Env{Cfg: Config{GameDir: dir, Threads: 4}}, p, "")
	if err != nil {
		t.Fatal(err)
	}
	if id != "樱之谷" {
		t.Fatal(id)
	}
	if b, err := os.ReadFile(filepath.Join(VersionDir(dir, id), "config", "sakura.toml")); err != nil || string(b) != "petals = true" {
		t.Fatal("override missing", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "escape.txt")); err == nil {
		t.Fatal("path escape")
	}
	v, _ := LoadVersion(dir, id)
	if v.EL == nil || !v.EL.Isolated || v.InheritsFrom != "1.21.1" {
		t.Fatal("meta", v.EL)
	}
	// second install gets a unique name
	id2, err := InstallMrpack(t.Context(), Env{Cfg: Config{GameDir: dir, Threads: 4}}, p, "")
	if err != nil || id2 != "樱之谷 (2)" {
		t.Fatal(id2, err)
	}
	if n := len(ListInstances(dir)); n != 3 {
		t.Fatal("instances", n)
	}
}

func TestContentToggle(t *testing.T) {
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "mods"), 0o755)
	os.WriteFile(filepath.Join(dir, "mods", "sodium.jar"), []byte("x"), 0o644)
	if err := ToggleContent(dir, "mod", "sodium.jar"); err != nil {
		t.Fatal(err)
	}
	l := ListContent(dir, "mod")
	if len(l) != 1 || l[0].Enabled || l[0].Name != "sodium" {
		t.Fatal(l)
	}
	if ToggleContent(dir, "mod", "../x") == nil {
		t.Fatal("escape allowed")
	}
}
