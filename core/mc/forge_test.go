package mc

import (
	"archive/zip"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const procSrc = `package proc;
import java.nio.file.*;
public class Main {
  public static void main(String[] a) throws Exception {
    String in = null, out = null, extra = "", mc = null;
    for (int i = 0; i < a.length; i += 2) {
      switch (a[i]) { case "--input": in = a[i+1]; break; case "--output": out = a[i+1]; break;
                      case "--extra": extra = a[i+1]; break; case "--mc": mc = a[i+1]; break; }
    }
    if (!Files.exists(Paths.get(mc))) { System.err.println("no mc jar " + mc); System.exit(3); }
    Path o = Paths.get(out); Files.createDirectories(o.getParent());
    Files.write(o, (new String(Files.readAllBytes(Paths.get(in))) + "|" + extra).getBytes());
    System.out.println("patched " + out);
  }
}`

func javaHome(t *testing.T) string {
	p, err := exec.LookPath("javac")
	if err != nil {
		t.Skip("no JDK")
	}
	real, _ := filepath.EvalSymlinks(p)
	return filepath.Dir(filepath.Dir(real))
}

func buildProcJar(t *testing.T, dir string) []byte {
	src := filepath.Join(dir, "src", "proc")
	os.MkdirAll(src, 0o755)
	os.WriteFile(filepath.Join(src, "Main.java"), []byte(procSrc), 0o644)
	out := filepath.Join(dir, "cls")
	if b, err := exec.Command("javac", "-d", out, filepath.Join(src, "Main.java")).CombinedOutput(); err != nil {
		t.Fatal(string(b))
	}
	jar := filepath.Join(dir, "proc.jar")
	if b, err := exec.Command("jar", "--create", "--file", jar, "--main-class", "proc.Main", "-C", out, ".").CombinedOutput(); err != nil {
		t.Fatal(string(b))
	}
	b, _ := os.ReadFile(jar)
	return b
}

func writeZip(t *testing.T, path string, files map[string][]byte) {
	f, _ := os.Create(path)
	zw := zip.NewWriter(f)
	for n, b := range files {
		w, _ := zw.Create(n)
		w.Write(b)
	}
	zw.Close()
	f.Close()
}

func TestModernForgeInstaller(t *testing.T) {
	home := javaHome(t)
	dir := setup(t)
	tmp := t.TempDir()
	procJar := buildProcJar(t, tmp)
	ver := map[string]any{
		"id": "1.21.1-forge-52.0.0", "inheritsFrom": "1.21.1", "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
		"arguments": map[string]any{
			"game": []string{"--launchTarget", "forgeclient"},
			"jvm":  []string{"-DignoreList=client-extra,${version_name}.jar", "-DlibraryDirectory=${library_directory}", "-p", "${library_directory}/x${classpath_separator}y"},
		},
		"libraries": []map[string]any{
			{"name": "net.minecraftforge:forge:1.21.1-52.0.0:universal", "downloads": map[string]any{"artifact": map[string]any{"path": "net/minecraftforge/forge/1.21.1-52.0.0/forge-1.21.1-52.0.0-universal.jar", "url": "", "sha1": "", "size": 0}}},
			{"name": "net.minecraftforge:forge:1.21.1-52.0.0:client", "downloads": map[string]any{"artifact": map[string]any{"path": "net/minecraftforge/forge/1.21.1-52.0.0/forge-1.21.1-52.0.0-client.jar", "url": "", "sha1": "", "size": 0}}},
		},
	}
	vb, _ := json.Marshal(ver)
	prof := map[string]any{
		"spec": 1, "json": "/version.json", "minecraft": "1.21.1",
		"data": map[string]any{
			"BINPATCH": map[string]string{"client": "/data/client.lzma", "server": "/data/server.lzma"},
			"PATCHED":  map[string]string{"client": "[net.minecraftforge:forge:1.21.1-52.0.0:client]", "server": "x"},
			"MAGIC":    map[string]string{"client": "'sakura'", "server": "'x'"},
			"MOJMAPS":  map[string]string{"client": "[net.minecraft:client:1.21.1-20240808:mappings@txt]", "server": "x"},
		},
		"processors": []map[string]any{
			{"sides": []string{"server"}, "jar": "net.test:proc:1.0", "args": []string{"--boom"}},
			// done by the launcher itself through the mirror; the jar doesn't even exist here
			{"jar": "net.minecraftforge:installertools:1.4.1", "args": []string{"--task", "DOWNLOAD_MOJMAPS", "--version", "{MINECRAFT_VERSION}", "--side", "{SIDE}", "--output", "{MOJMAPS}"}},
			{"jar": "net.test:proc:1.0", "classpath": []string{}, "args": []string{"--input", "{BINPATCH}", "--output", "{PATCHED}", "--extra", "{MAGIC}", "--mc", "{MINECRAFT_JAR}"}},
		},
		"libraries": []any{},
	}
	pb, _ := json.Marshal(prof)
	inst := filepath.Join(tmp, "installer.jar")
	writeZip(t, inst, map[string][]byte{
		"install_profile.json": pb, "version.json": vb, "data/client.lzma": []byte("binpatch"),
		"maven/net/test/proc/1.0/proc-1.0.jar":                                           procJar,
		"maven/net/minecraftforge/forge/1.21.1-52.0.0/forge-1.21.1-52.0.0-universal.jar": []byte("universal"),
	})
	// the vanilla json advertises its mappings (served locally)
	maps := []byte("# mojang mappings\n")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(maps) }))
	defer srv.Close()
	var van map[string]any
	vp := VersionJSON(dir, "1.21.1")
	vb0, _ := os.ReadFile(vp)
	json.Unmarshal(vb0, &van)
	dl, _ := van["downloads"].(map[string]any)
	if dl == nil {
		dl = map[string]any{}
	}
	dl["client_mappings"] = map[string]any{"url": srv.URL + "/client.txt", "sha1": sha1hex(maps), "size": len(maps)}
	van["downloads"] = dl
	vb1, _ := json.Marshal(van)
	os.WriteFile(vp, vb1, 0o644)
	// pretend 1.21.1 wants a Java we have
	env := Env{Cfg: Config{GameDir: dir, Threads: 4, JavaMode: "manual", JavaPath: filepath.Join(home, "bin", "java")}, DataDir: t.TempDir()}
	id, err := runForgeInstaller(t.Context(), env, inst, filepath.Join(tmp, "work"), "1.21.1", "锻造之旅")
	if err != nil {
		t.Fatal(err)
	}
	if b, err := os.ReadFile(filepath.Join(dir, "libraries", "net/minecraft/client/1.21.1-20240808/client-1.21.1-20240808-mappings.txt")); err != nil || string(b) != string(maps) {
		t.Fatalf("mappings %q %v", b, err)
	}
	out, err := os.ReadFile(filepath.Join(dir, "libraries", "net/minecraftforge/forge/1.21.1-52.0.0/forge-1.21.1-52.0.0-client.jar"))
	if err != nil || string(out) != "binpatch|sakura" {
		t.Fatalf("processor output %q %v", out, err)
	}
	acc, _ := OfflineAccount("D0cK")
	cmd, _, err := BuildCommand(LaunchOptions{GameDir: dir, ID: id, Java: &Java{Path: "java", Major: 21}, Account: acc, MemoryMB: 4096})
	if err != nil {
		t.Fatal(err)
	}
	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "-DignoreList=client-extra,锻造之旅.jar") || !strings.Contains(args, "--launchTarget forgeclient") ||
		!strings.Contains(args, "BootstrapLauncher") || !strings.Contains(args, "锻造之旅"+string(os.PathSeparator)+"锻造之旅.jar") {
		t.Fatal(args)
	}
	if _, err := os.Stat(filepath.Join(VersionDir(dir, id), id+".jar")); err != nil {
		t.Fatal("client jar not copied")
	}
	if a := AutoMemory(true); a < 2048 {
		t.Fatal(a)
	}
}

func TestLegacyForgeInstaller(t *testing.T) {
	dir := setup(t)
	tmp := t.TempDir()
	lw := filepath.Join(dir, "libraries", "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
	os.MkdirAll(filepath.Dir(lw), 0o755)
	os.WriteFile(lw, []byte("lw"), 0o644)
	prof := `{"install":{"path":"net.minecraftforge:forge:1.12.2-14.23.5.2859","filePath":"forge-1.12.2-14.23.5.2859.jar","minecraft":"1.12.2"},
	"versionInfo":{"id":"1.12.2-forge-14.23.5.2859","inheritsFrom":"1.12.2","mainClass":"net.minecraft.launchwrapper.Launch",
	 "minecraftArguments":"--username ${auth_player_name} --version ${version_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker",
	 "libraries":[{"name":"net.minecraftforge:forge:1.12.2-14.23.5.2859","url":"http://files.minecraftforge.net/maven/"},
	              {"name":"net.minecraft:launchwrapper:1.12","serverreq":true},
	              {"name":"com.typesafe.akka:akka-actor_2.11:2.3.3","url":"http://files.minecraftforge.net/maven/","checksums":["a"],"serverreq":true,"clientreq":false}]}}`
	inst := filepath.Join(tmp, "installer.jar")
	writeZip(t, inst, map[string][]byte{"install_profile.json": []byte(prof), "forge-1.12.2-14.23.5.2859.jar": []byte("universal")})
	env := Env{Cfg: Config{GameDir: dir, Threads: 2}}
	id, err := runForgeInstaller(t.Context(), env, inst, tmp, "1.21.1", "老派模组")
	if err != nil {
		t.Fatal(err)
	}
	v, _ := Resolve(dir, id)
	if len(v.Libraries) != 2+4-0 && !strings.Contains(v.MainClass, "launchwrapper") {
		t.Fatal(len(v.Libraries))
	}
	for _, l := range v.Libraries {
		if strings.Contains(l.Name, "akka") {
			t.Fatal("server-only lib kept")
		}
		if strings.Contains(l.URL, "http://") {
			t.Fatal("http url kept", l.URL)
		}
	}
	acc, _ := OfflineAccount("D0cK")
	cmd, _, err := BuildCommand(LaunchOptions{GameDir: dir, ID: id, Java: &Java{Path: "java", Major: 8}, Account: acc})
	if err != nil {
		t.Fatal(err)
	}
	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker") || !strings.Contains(args, "forge-1.12.2-14.23.5.2859.jar") {
		t.Fatal(args)
	}
}

func TestNeoGame(t *testing.T) {
	for v, g := range map[string]string{"21.1.77": "1.21.1", "20.4.237": "1.20.4", "21.0.167": "1.21", "20.2.3-beta": "1.20.2", "26.1.0.5-beta": "26.1"} {
		if NeoGame(v) != g {
			t.Errorf("%s -> %s want %s", v, NeoGame(v), g)
		}
	}
	if forgePart("1.7.10-10.13.4.1614-1.7.10", "1.7.10") != "10.13.4.1614" || !verLess("47.2.0", "47.10.1") {
		t.Fatal("forge version parse")
	}
}
