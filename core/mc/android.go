package mc

// Android support. The Android app starts this core as a child process and
// runs the game itself in its own process (an in-process JVM drawing through
// the app's GLFW/EGL bridge), so on Android the core prepares a launch spec
// instead of spawning java.
//
// This file builds on every platform (gated by Android) so it can be tested
// on a desktop.

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/ulikunitz/xz"
)

// Android is true when running inside the Android app.
var Android = runtime.GOOS == "android" || os.Getenv("ERIRILILY_FAKE_ANDROID") != ""

// OpenHook shows a folder or link through the app shell (Android).
var OpenHook func(p string) error

// AndroidEnv is what the app tells the core about the device and the APK.
type AndroidEnv struct {
	NativeDir string `json:"nativeDir"` // the app's native library dir (bridge, renderers, LWJGL natives)
	APK       string `json:"apk"`       // path of the installed APK (bundled runtimes are read from it)
	CacheDir  string `json:"cacheDir"`
	ABI       string `json:"abi"` // arm64-v8a | armeabi-v7a | x86_64 | x86
	Device    Device `json:"device"`
}

// Device describes the phone; the app fills it in.
type Device struct {
	Model       string  `json:"model"`
	Brand       string  `json:"brand"`
	SoC         string  `json:"soc"`
	SDK         int     `json:"sdk"`
	Release     string  `json:"release"`
	Cores       int     `json:"cores"`
	BigCores    int     `json:"bigCores"`
	MaxFreqMHz  int     `json:"maxFreqMHz"`
	RAMMB       int     `json:"ramMB"`
	Refresh     float64 `json:"refresh"`     // highest display refresh rate
	ScreenW     int     `json:"screenW"`     // physical pixels, landscape
	ScreenH     int     `json:"screenH"`     //
	GLRenderer  string  `json:"glRenderer"`  // GL_RENDERER of the GPU
	GLESVersion string  `json:"glesVersion"` // GL_VERSION
	Vulkan      bool    `json:"vulkan"`
	PerfHint    bool    `json:"perfHint"` // ADPF available
	Sustained   bool    `json:"sustained"`
	Thermal     int     `json:"thermal"` // PowerManager thermal status
}

var AEnv AndroidEnv

// InitAndroid reads the environment the app passed in and fixes networking.
func InitAndroid() {
	AEnv.NativeDir = os.Getenv("ERIRILILY_NATIVE_DIR")
	AEnv.APK = os.Getenv("ERIRILILY_APK")
	AEnv.CacheDir = os.Getenv("ERIRILILY_CACHE")
	AEnv.ABI = os.Getenv("ERIRILILY_ABI")
	if d := os.Getenv("ERIRILILY_DEVICE"); d != "" {
		_ = json.Unmarshal([]byte(d), &AEnv.Device)
	}
	if AEnv.ABI == "" {
		AEnv.ABI = map[string]string{"arm64": "arm64-v8a", "arm": "armeabi-v7a", "amd64": "x86_64", "386": "x86"}[runtime.GOARCH]
	}
	if runtime.GOOS == "android" {
		setupDNS(strings.Fields(strings.ReplaceAll(os.Getenv("ERIRILILY_DNS"), ",", " ")))
	}
}

// Without cgo, Go on Android cannot read the system resolver config (there is
// no /etc/resolv.conf): ask the network's DNS servers the app reported, then
// well-known public resolvers (domestic ones first).
func setupDNS(servers []string) {
	servers = append(servers, "223.5.5.5", "119.29.29.29", "114.114.114.114", "1.1.1.1", "8.8.8.8")
	var addrs []string
	for _, s := range servers {
		if ip := net.ParseIP(strings.Trim(s, "[]")); ip != nil {
			addrs = append(addrs, net.JoinHostPort(ip.String(), "53"))
		}
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var last error
			d := net.Dialer{Timeout: 2 * time.Second}
			for _, a := range addrs {
				c, err := d.DialContext(ctx, network, a)
				if err == nil {
					return c, nil
				}
				last = err
			}
			return nil, last
		},
	}
}

// ---------------------------------------------------------------- Java runtimes

// jvmLib finds libjvm.so in an Android JRE.
func jvmLib(home string) string {
	for _, d := range []string{"lib/server", "lib/aarch64/server", "lib/arm/server", "lib/arm/client",
		"lib/amd64/server", "lib/i386/server", "lib/i386/client"} {
		p := filepath.Join(home, filepath.FromSlash(d), "libjvm.so")
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

// probeAndroidJava reads an Android JRE: there is no java executable to run,
// only libjvm.so the app loads.
func probeAndroidJava(home string) *Java {
	lib := jvmLib(home)
	if lib == "" {
		return nil
	}
	j := &Java{Path: lib, Home: home, Bits64: !strings.Contains(lib, "/arm/") && !strings.Contains(lib, "/i386/")}
	if b, err := os.ReadFile(filepath.Join(home, "release")); err == nil {
		for _, l := range strings.Split(string(b), "\n") {
			if m := reReleaseKV.FindStringSubmatch(strings.TrimSpace(l)); m != nil {
				switch m[1] {
				case "JAVA_VERSION":
					j.Version = m[2]
				case "IMPLEMENTOR":
					j.Vendor = m[2]
				}
			}
		}
	}
	if j.Version == "" {
		// no release file: the folder name usually says it (jre-17, jre8, …)
		name := strings.TrimLeft(strings.ToLower(filepath.Base(home)), "abcdefghijklmnopqrstuvwxyz-_")
		if n := parseMajor(name); n > 0 {
			j.Version = strconv.Itoa(n)
		}
	}
	j.Major = parseMajor(j.Version)
	if j.Major == 0 {
		return nil
	}
	return j
}

func androidRuntimes(dataDir string) []*Java {
	var out []*Java
	root := filepath.Join(dataDir, "runtime")
	ents, _ := os.ReadDir(root)
	for _, e := range ents {
		if !e.IsDir() || e.Name() == "lwjgl3" {
			continue
		}
		if j := probeAndroidJava(filepath.Join(root, e.Name())); j != nil {
			j.Managed = true
			out = append(out, j)
		}
	}
	sort.SliceStable(out, func(a, b int) bool { return out[a].Major > out[b].Major })
	return out
}

// pickAndroidJava is more forgiving than PickJava: Android JREs come in
// 8 / 17 / 21 (/ 25), and the game runs on a newer one in most cases.
func pickAndroidJava(all []*Java, major int) *Java {
	if j := pickJava(all, major); j != nil {
		return j
	}
	var best *Java
	for _, j := range all {
		if j.Major < major {
			continue
		}
		if major <= 8 && j.Major > 17 {
			continue // 1.16 and older: Java 17 still works for vanilla/Fabric, 21 rarely does
		}
		if best == nil || j.Major < best.Major {
			best = j
		}
	}
	return best
}

// BundledRuntimes lists the JREs shipped inside the APK (assets/runtime/jre<N>.tar.xz).
func BundledRuntimes() []int {
	if AEnv.APK == "" {
		return nil
	}
	z, err := zip.OpenReader(AEnv.APK)
	if err != nil {
		return nil
	}
	defer z.Close()
	var out []int
	for _, f := range z.File {
		if strings.HasPrefix(f.Name, "assets/runtime/jre") && strings.HasSuffix(f.Name, ".tar.xz") {
			n, _ := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(f.Name, "assets/runtime/jre"), ".tar.xz"))
			if n > 0 {
				out = append(out, n)
			}
		}
	}
	sort.Ints(out)
	return out
}

// RuntimeMirror is a URL template for JRE downloads on Android ({major}, {abi}).
var RuntimeMirror = ""

// installAndroidRuntime installs a JRE for the given major version: from the
// APK if it ships one, otherwise from the configured mirror.
func installAndroidRuntime(ctx context.Context, dataDir string, major int, threads int, rep Reporter) (*Java, error) {
	want := major
	have := BundledRuntimes()
	pick := 0
	for _, n := range have {
		if n == want || (pick == 0 && n > want && !(want <= 8 && n > 17)) {
			pick = n
		}
	}
	if pick > 0 {
		return installBundledJRE(ctx, dataDir, pick, rep)
	}
	if RuntimeMirror == "" {
		return nil, fmt.Errorf("没有 Java %d 运行时：请在「设置 › 运行环境」里导入 Java %d（.tar.xz / .zip），或填写下载地址", major, major)
	}
	u := strings.NewReplacer("{major}", strconv.Itoa(major), "{abi}", AEnv.ABI).Replace(RuntimeMirror)
	tmp := filepath.Join(dataDir, "cache", fmt.Sprintf("jre%d-download", major))
	_ = os.Remove(tmp)
	if err := Batch(ctx, fmt.Sprintf("Java %d", major), []File{{URL: u, Path: tmp}}, threads, rep); err != nil {
		return nil, err
	}
	defer os.Remove(tmp)
	return InstallRuntimeArchive(ctx, dataDir, tmp, filepath.Base(u))
}

func installBundledJRE(ctx context.Context, dataDir string, major int, rep Reporter) (*Java, error) {
	z, err := zip.OpenReader(AEnv.APK)
	if err != nil {
		return nil, err
	}
	defer z.Close()
	name := fmt.Sprintf("assets/runtime/jre%d.tar.xz", major)
	for _, f := range z.File {
		if f.Name != name {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		if rep != nil {
			rep(Progress{Stage: fmt.Sprintf("解压 Java %d", major), Total: 1})
		}
		home := filepath.Join(dataDir, "runtime", fmt.Sprintf("jre-%d", major))
		j, err := unpackRuntime(ctx, rc, "x.tar.xz", home)
		if rep != nil {
			rep(Progress{Stage: fmt.Sprintf("解压 Java %d", major), Done: 1, Total: 1})
		}
		return j, err
	}
	return nil, fmt.Errorf("安装包里没有 Java %d", major)
}

// InstallRuntimeArchive installs a JRE from a .tar.xz / .tar.gz / .zip file
// the user picked (or downloaded).
func InstallRuntimeArchive(ctx context.Context, dataDir, file, name string) (*Java, error) {
	f, err := os.Open(file)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	tmpHome := filepath.Join(dataDir, "runtime", ".import-"+strconv.FormatInt(time.Now().UnixNano(), 36))
	j, err := unpackRuntime(ctx, f, name, tmpHome)
	if err != nil {
		_ = os.RemoveAll(tmpHome)
		return nil, err
	}
	final := filepath.Join(dataDir, "runtime", fmt.Sprintf("jre-%d", j.Major))
	_ = os.RemoveAll(final)
	if err := os.Rename(tmpHome, final); err != nil {
		return nil, err
	}
	resetJavaCache()
	return probeAndroidJava(final), nil
}

func resetJavaCache() {
	javaMu.Lock()
	javaCache = nil
	javaMu.Unlock()
}

// unpackRuntime extracts an archive into home and normalises the layout so
// home/release and the lib folder sit at the top.
func unpackRuntime(ctx context.Context, r io.Reader, name, home string) (*Java, error) {
	tmp := home + ".part"
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return nil, err
	}
	var err error
	low := strings.ToLower(name)
	switch {
	case strings.HasSuffix(low, ".zip"):
		err = unzipStream(r, tmp)
	case strings.HasSuffix(low, ".tar.gz"), strings.HasSuffix(low, ".tgz"):
		var gz *gzip.Reader
		if gz, err = gzip.NewReader(r); err == nil {
			err = untar(ctx, gz, tmp)
		}
	default: // .tar.xz (Pojav / FCL style JREs)
		var xr *xz.Reader
		if xr, err = xz.NewReader(r); err == nil {
			err = untar(ctx, xr, tmp)
		}
	}
	if err != nil {
		_ = os.RemoveAll(tmp)
		return nil, fmt.Errorf("解压运行时失败：%w", err)
	}
	// Pojav ships universal.tar.xz + bin-<arch>.tar.xz packed together: unpack the nested ones in place
	for _, inner := range []string{"universal.tar.xz", "bin-" + jreArch() + ".tar.xz"} {
		p := filepath.Join(tmp, inner)
		if f, err := os.Open(p); err == nil {
			if xr, err := xz.NewReader(f); err == nil {
				err = untar(ctx, xr, tmp)
				if err != nil {
					f.Close()
					return nil, err
				}
			}
			f.Close()
		}
	}
	root := findJREHome(tmp, 3)
	if root == "" {
		_ = os.RemoveAll(tmp)
		return nil, errors.New("这不是安卓可用的 Java 运行时（找不到 libjvm.so）")
	}
	_ = os.RemoveAll(home)
	if root == tmp {
		err = os.Rename(tmp, home)
	} else {
		err = os.Rename(root, home)
		_ = os.RemoveAll(tmp)
	}
	if err != nil {
		return nil, err
	}
	fixJRE(home)
	j := probeAndroidJava(home)
	if j == nil {
		return nil, errors.New("运行时安装后无法识别版本")
	}
	resetJavaCache()
	return j, nil
}

func jreArch() string {
	switch AEnv.ABI {
	case "armeabi-v7a":
		return "arm"
	case "x86_64":
		return "x86_64"
	case "x86":
		return "x86"
	}
	return "aarch64"
}

func findJREHome(dir string, depth int) string {
	if jvmLib(dir) != "" {
		return dir
	}
	if depth == 0 {
		return ""
	}
	ents, _ := os.ReadDir(dir)
	for _, e := range ents {
		if e.IsDir() {
			if h := findJREHome(filepath.Join(dir, e.Name()), depth-1); h != "" {
				return h
			}
		}
	}
	return ""
}

// fixJRE: some builds miss a release file; write one so the version is known.
func fixJRE(home string) {
	if _, err := os.Stat(filepath.Join(home, "release")); err == nil {
		return
	}
	major := 8
	if _, err := os.Stat(filepath.Join(home, "lib", "modules")); err == nil {
		major = 17
		if _, err := os.Stat(filepath.Join(home, "lib", "libsyslookup.so")); err == nil {
			major = 21
		}
	}
	_ = os.WriteFile(filepath.Join(home, "release"), []byte(fmt.Sprintf("JAVA_VERSION=\"%d\"\n", major)), 0o644)
}

func safeJoin(root, name string) (string, error) {
	p := filepath.Join(root, filepath.FromSlash(name))
	if p != root && !strings.HasPrefix(p, root+string(os.PathSeparator)) {
		return "", fmt.Errorf("非法路径 %s", name)
	}
	return p, nil
}

func untar(ctx context.Context, r io.Reader, dir string) error {
	tr := tar.NewReader(r)
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		h, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		p, err := safeJoin(dir, h.Name)
		if err != nil {
			return err
		}
		switch h.Typeflag {
		case tar.TypeDir:
			_ = os.MkdirAll(p, 0o755)
		case tar.TypeSymlink:
			_ = os.MkdirAll(filepath.Dir(p), 0o755)
			_ = os.Remove(p)
			if filepath.IsAbs(h.Linkname) {
				continue // never point outside the runtime
			}
			_ = os.Symlink(h.Linkname, p)
		case tar.TypeLink:
			src, err := safeJoin(dir, h.Linkname)
			if err == nil {
				_ = os.Remove(p)
				_ = os.MkdirAll(filepath.Dir(p), 0o755)
				if os.Link(src, p) != nil {
					_ = copyFile(src, p)
				}
			}
		case tar.TypeReg:
			_ = os.MkdirAll(filepath.Dir(p), 0o755)
			mode := os.FileMode(0o644)
			if h.FileInfo().Mode()&0o111 != 0 {
				mode = 0o755
			}
			f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
			if err != nil {
				return err
			}
			_, err = io.Copy(f, tr)
			f.Close()
			if err != nil {
				return err
			}
		}
	}
}

func unzipStream(r io.Reader, dir string) error {
	tmp, err := os.CreateTemp(filepath.Dir(dir), "rt-*.zip")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := io.Copy(tmp, r); err != nil {
		tmp.Close()
		return err
	}
	tmp.Close()
	z, err := zip.OpenReader(tmp.Name())
	if err != nil {
		return err
	}
	defer z.Close()
	for _, f := range z.File {
		p, err := safeJoin(dir, f.Name)
		if err != nil {
			return err
		}
		if f.FileInfo().IsDir() {
			_ = os.MkdirAll(p, 0o755)
			continue
		}
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
		if err != nil {
			rc.Close()
			return err
		}
		_, err = io.Copy(out, rc)
		rc.Close()
		out.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

// RemoveRuntime deletes a JRE the launcher installed.
func RemoveRuntime(dataDir, home string) error {
	root := filepath.Join(dataDir, "runtime") + string(os.PathSeparator)
	if !strings.HasPrefix(filepath.Clean(home)+string(os.PathSeparator), root) || filepath.Base(home) == "lwjgl3" {
		return errors.New("只能删除启动器安装的运行时")
	}
	resetJavaCache()
	return os.RemoveAll(home)
}

// ---------------------------------------------------------------- LWJGL + renderers

// lwjglDir holds the LWJGL 3 jars built for Android, copied out of the APK.
func lwjglDir(dataDir string) string { return filepath.Join(dataDir, "runtime", "lwjgl3") }

// EnsureLWJGL extracts assets/lwjgl3/*.jar when the APK changed.
func EnsureLWJGL(dataDir string) ([]string, error) {
	dir := lwjglDir(dataDir)
	st, err := os.Stat(AEnv.APK)
	if err != nil {
		return nil, errors.New("找不到安装包，无法准备 LWJGL")
	}
	stamp := fmt.Sprintf("%d-%d", st.Size(), st.ModTime().Unix())
	if b, err := os.ReadFile(filepath.Join(dir, ".stamp")); err != nil || string(b) != stamp {
		z, err := zip.OpenReader(AEnv.APK)
		if err != nil {
			return nil, err
		}
		_ = os.RemoveAll(dir)
		_ = os.MkdirAll(dir, 0o755)
		for _, f := range z.File {
			if !strings.HasPrefix(f.Name, "assets/lwjgl3/") || !strings.HasSuffix(f.Name, ".jar") {
				continue
			}
			rc, err := f.Open()
			if err != nil {
				z.Close()
				return nil, err
			}
			out, err := os.Create(filepath.Join(dir, filepath.Base(f.Name)))
			if err == nil {
				_, err = io.Copy(out, rc)
				out.Close()
			}
			rc.Close()
			if err != nil {
				z.Close()
				return nil, err
			}
		}
		z.Close()
		_ = os.WriteFile(filepath.Join(dir, ".stamp"), []byte(stamp), 0o644)
	}
	ents, _ := os.ReadDir(dir)
	var jars []string
	for _, e := range ents {
		if strings.HasSuffix(e.Name(), ".jar") {
			jars = append(jars, filepath.Join(dir, e.Name()))
		}
	}
	if len(jars) == 0 {
		return nil, errors.New("安装包缺少 LWJGL 运行库，请重新安装 EririLily")
	}
	return jars, nil
}

// Renderer is an OpenGL implementation the game can draw with.
type Renderer struct {
	ID      string            `json:"id"`
	Name    string            `json:"name"`
	Desc    string            `json:"desc"`
	Lib     string            `json:"lib"`     // absolute path of the libGL replacement
	GLES    int               `json:"gles"`    // GLES context version the bridge creates (2 or 3)
	MinGame string            `json:"minGame"` // oldest game version it is meant for ("" = any)
	MaxGame string            `json:"maxGame"` // newest game version it handles well ("" = any)
	Env     map[string]string `json:"env,omitempty"`
	Plugin  bool              `json:"plugin"`
}

// Renderers lists the built-in renderers present in the APK and imported plugins.
func Renderers(dataDir string) []Renderer {
	var out []Renderer
	builtin := []Renderer{
		{ID: "mobileglues", Name: "MobileGlues", Desc: "OpenGL 4 over GLES 3.2 · 1.17 及以上推荐", Lib: "libmobileglues.so", GLES: 3, MinGame: "1.17"},
		{ID: "gl4es", Name: "GL4ES", Desc: "OpenGL 2.1 · 1.16.5 及以下最稳", Lib: "libgl4es.so", GLES: 2, MaxGame: "1.16.5",
			Env: map[string]string{"LIBGL_ES": "2", "LIBGL_MIPMAP": "3", "LIBGL_NORMALIZE": "1", "LIBGL_NOINTOVLHACK": "1", "LIBGL_NOERROR": "1"}},
	}
	for _, r := range builtin {
		p := filepath.Join(AEnv.NativeDir, r.Lib)
		if _, err := os.Stat(p); err == nil {
			r.Lib = p
			out = append(out, r)
		}
	}
	// plugins: <data>/renderers/<id>/renderer.json + the library
	root := filepath.Join(dataDir, "renderers")
	ents, _ := os.ReadDir(root)
	for _, e := range ents {
		var r Renderer
		if readJSON(filepath.Join(root, e.Name(), "renderer.json"), &r) != nil || r.Lib == "" {
			continue
		}
		r.ID, r.Plugin = "plugin:"+e.Name(), true
		r.Lib = filepath.Join(root, e.Name(), filepath.Base(r.Lib))
		if r.GLES == 0 {
			r.GLES = 3
		}
		if r.Name == "" {
			r.Name = e.Name()
		}
		out = append(out, r)
	}
	return out
}

// ImportRenderer installs a renderer plugin: a single .so (or a zip with
// renderer.json + libraries).
func ImportRenderer(dataDir, file, name string) (*Renderer, error) {
	base := strings.TrimSuffix(strings.TrimSuffix(filepath.Base(name), ".so"), ".zip")
	base = strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == '.' || r == ' ' {
			return '_'
		}
		return r
	}, base)
	if base == "" {
		base = "renderer"
	}
	dir := filepath.Join(dataDir, "renderers", base)
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	if strings.HasSuffix(strings.ToLower(name), ".zip") {
		f, err := os.Open(file)
		if err != nil {
			return nil, err
		}
		err = unzipStream(f, dir)
		f.Close()
		if err != nil {
			return nil, err
		}
		var r Renderer
		if readJSON(filepath.Join(dir, "renderer.json"), &r) != nil || r.Lib == "" {
			return nil, errors.New("渲染器插件缺少 renderer.json")
		}
	} else {
		lib := strings.TrimSuffix(filepath.Base(name), ".so") + ".so"
		if !strings.HasPrefix(lib, "lib") {
			lib = "lib" + lib
		}
		if err := copyFile(file, filepath.Join(dir, lib)); err != nil {
			return nil, err
		}
		_ = writeJSON(filepath.Join(dir, "renderer.json"), Renderer{Name: base, Lib: lib, GLES: 3, Desc: "导入的渲染器"})
	}
	for _, r := range Renderers(dataDir) {
		if r.ID == "plugin:"+base {
			return &r, nil
		}
	}
	return nil, errors.New("渲染器导入失败")
}

// gameCmp compares Minecraft versions ("1.16.5" < "1.17" < "26.1"); snapshots compare as 0.
func gameCmp(a, b string) int {
	pa, pb := versionParts(a), versionParts(b)
	for i := 0; i < 3; i++ {
		if pa[i] != pb[i] {
			if pa[i] < pb[i] {
				return -1
			}
			return 1
		}
	}
	return 0
}

func versionParts(v string) [3]int {
	var out [3]int
	for i, p := range strings.SplitN(strings.SplitN(v, "-", 2)[0], ".", 3) {
		n, _ := strconv.Atoi(strings.TrimFunc(p, func(r rune) bool { return r < '0' || r > '9' }))
		out[i] = n
	}
	return out
}

// PickRenderer resolves the configured renderer for a game version.
func PickRenderer(dataDir, want, game string) (Renderer, error) {
	all := Renderers(dataDir)
	if len(all) == 0 {
		return Renderer{}, errors.New("安装包里没有可用的渲染器")
	}
	if want != "" && want != "auto" {
		for _, r := range all {
			if r.ID == want {
				return r, nil
			}
		}
	}
	modern := game == "" || gameCmp(game, "1.17") >= 0
	for _, r := range all {
		if modern && r.ID == "mobileglues" || !modern && r.ID == "gl4es" {
			return r, nil
		}
	}
	return all[0], nil
}

// usesLWJGL2 reports 1.12.2-and-older versions that need LWJGL 2.
func usesLWJGL2(v *Version) bool {
	for _, l := range v.Libraries {
		if strings.HasPrefix(l.Name, "org.lwjgl.lwjgl:lwjgl:2.") {
			return true
		}
	}
	return false
}
