package mc

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
)

type Java struct {
	Path    string `json:"path"` // javaw.exe / java
	Home    string `json:"home"`
	Version string `json:"version"` // e.g. 17.0.10
	Major   int    `json:"major"`
	Bits64  bool   `json:"bits64"`
	Vendor  string `json:"vendor"`
	Managed bool   `json:"managed"` // downloaded by EririLily
}

func javaExe() string {
	if runtime.GOOS == "windows" {
		return "javaw.exe"
	}
	return "java"
}

func parseMajor(v string) int {
	v = strings.TrimPrefix(v, "1.")
	n := 0
	for _, c := range v {
		if c < '0' || c > '9' {
			break
		}
		n = n*10 + int(c-'0')
	}
	return n
}

var reReleaseKV = regexp.MustCompile(`^([A-Z_]+)="?([^"]*)"?`)

// probeJava reads <home>/release (no process spawn: fast).
func probeJava(home string) *Java {
	exe := filepath.Join(home, "bin", javaExe())
	if _, err := os.Stat(exe); err != nil {
		return nil
	}
	j := &Java{Path: exe, Home: home, Bits64: true}
	if f, err := os.Open(filepath.Join(home, "release")); err == nil {
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			m := reReleaseKV.FindStringSubmatch(sc.Text())
			if m == nil {
				continue
			}
			switch m[1] {
			case "JAVA_VERSION":
				j.Version = m[2]
			case "IMPLEMENTOR":
				j.Vendor = m[2]
			case "OS_ARCH":
				j.Bits64 = !(m[2] == "x86" || m[2] == "i386" || m[2] == "i586")
			}
		}
		f.Close()
	}
	if j.Version == "" {
		// very old JREs lack a release file: ask the binary
		out, err := exec.Command(filepath.Join(home, "bin", strings.Replace(javaExe(), "javaw", "java", 1)), "-version").CombinedOutput()
		if err == nil {
			if m := regexp.MustCompile(`version "([^"]+)"`).FindSubmatch(out); m != nil {
				j.Version = string(m[1])
			}
			j.Bits64 = strings.Contains(string(out), "64-Bit")
		}
	}
	j.Major = parseMajor(j.Version)
	if j.Major == 0 {
		return nil
	}
	return j
}

func candidateHomes(gameDir, dataDir string) []string {
	var roots, homes []string
	add := func(h string) {
		if h != "" {
			homes = append(homes, h)
		}
	}
	add(os.Getenv("JAVA_HOME"))
	for _, p := range filepath.SplitList(os.Getenv("PATH")) {
		if _, err := os.Stat(filepath.Join(p, javaExe())); err == nil {
			if real, err := filepath.EvalSymlinks(filepath.Join(p, javaExe())); err == nil {
				add(filepath.Dir(filepath.Dir(real)))
			}
		}
	}
	roots = append(roots, filepath.Join(dataDir, "runtime"))
	if runtime.GOOS == "windows" {
		for _, pf := range []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)"), os.Getenv("ProgramW6432")} {
			if pf == "" {
				continue
			}
			for _, v := range []string{"Java", "Eclipse Adoptium", "Eclipse Foundation", "Zulu", "Microsoft", "BellSoft",
				"Amazon Corretto", "Semeru", "AdoptOpenJDK", "Liberica", "GraalVM", "Huawei", "Tencent", "Alibaba", "Dragonwell"} {
				roots = append(roots, filepath.Join(pf, v))
			}
			roots = append(roots, filepath.Join(pf, "Minecraft Launcher", "runtime"))
		}
		if la := os.Getenv("LOCALAPPDATA"); la != "" {
			roots = append(roots, filepath.Join(la, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"))
			walkHomes(filepath.Join(la, "Programs"), 2, add) // big folder: only look two levels down
		}
		if ad := os.Getenv("APPDATA"); ad != "" {
			roots = append(roots, filepath.Join(ad, ".minecraft", "runtime"), filepath.Join(ad, ".hmcl", "java"))
		}
		if up := os.Getenv("USERPROFILE"); up != "" {
			roots = append(roots, filepath.Join(up, ".jdks"))
		}
	} else {
		roots = append(roots, "/usr/lib/jvm", "/usr/java", "/opt/java", "/Library/Java/JavaVirtualMachines")
	}
	roots = append(roots, filepath.Join(gameDir, "runtime"))
	// walk up to 4 levels under each root looking for bin/java(w)
	for _, r := range roots {
		walkHomes(r, 4, add)
	}
	return homes
}

func walkHomes(dir string, depth int, add func(string)) {
	if depth < 0 {
		return
	}
	if _, err := os.Stat(filepath.Join(dir, "bin", javaExe())); err == nil {
		add(dir)
		return
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range ents {
		if e.IsDir() || e.Type()&os.ModeSymlink != 0 {
			walkHomes(filepath.Join(dir, e.Name()), depth-1, add)
		}
	}
}

var (
	javaMu    sync.Mutex
	javaCache []*Java
)

// DetectJava scans the machine for Java installations.
func DetectJava(gameDir, dataDir string, refresh bool) []*Java {
	javaMu.Lock()
	defer javaMu.Unlock()
	if javaCache != nil && !refresh {
		return javaCache
	}
	if Android {
		javaCache = androidRuntimes(dataDir)
		return javaCache
	}
	seen := map[string]bool{}
	var out []*Java
	for _, h := range candidateHomes(gameDir, dataDir) {
		h = filepath.Clean(h)
		key := strings.ToLower(h)
		if seen[key] {
			continue
		}
		seen[key] = true
		if j := probeJava(h); j != nil {
			j.Managed = strings.HasPrefix(strings.ToLower(h), strings.ToLower(filepath.Join(dataDir, "runtime")))
			out = append(out, j)
		}
	}
	sort.SliceStable(out, func(a, b int) bool {
		if out[a].Major != out[b].Major {
			return out[a].Major > out[b].Major
		}
		return out[a].Bits64 && !out[b].Bits64
	})
	javaCache = out
	return out
}

func requiredJava(v *Version) (major int, component string) {
	if v.JavaVersion != nil && v.JavaVersion.MajorVersion > 0 {
		return v.JavaVersion.MajorVersion, v.JavaVersion.Component
	}
	return 8, "jre-legacy"
}

// PickJava chooses the best installed Java for a version, or nil.
func PickJava(all []*Java, major int) *Java {
	if Android {
		return pickAndroidJava(all, major)
	}
	return pickJava(all, major)
}

func pickJava(all []*Java, major int) *Java {
	var best *Java
	for _, j := range all {
		if !j.Bits64 && runtime.GOARCH != "386" {
			continue
		}
		ok := j.Major == major || (major >= 16 && j.Major >= major && j.Major <= major+5)
		if !ok {
			continue
		}
		// prefer the exact major, then the smallest newer one
		if best == nil || (j.Major == major && best.Major != major) || (best.Major != major && j.Major < best.Major) {
			best = j
		}
	}
	return best
}

// ---------------------------------------------------------------- Mojang Java runtime

const runtimeIndex = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"

func runtimePlatform() string {
	switch runtime.GOOS {
	case "windows":
		switch runtime.GOARCH {
		case "arm64":
			return "windows-arm64"
		case "386":
			return "windows-x86"
		}
		return "windows-x64"
	case "darwin":
		if runtime.GOARCH == "arm64" {
			return "mac-os-arm64"
		}
		return "mac-os"
	}
	if runtime.GOARCH == "386" {
		return "linux-i386"
	}
	return "linux"
}

// InstallRuntime downloads Mojang's official Java runtime component.
func InstallRuntime(ctx context.Context, dataDir, component string, threads int, rep Reporter) (*Java, error) {
	if component == "" {
		component = "jre-legacy"
	}
	if Android {
		return installAndroidRuntime(ctx, dataDir, componentMajor(component), threads, rep)
	}
	var all map[string]map[string][]struct {
		Manifest struct {
			URL  string `json:"url"`
			SHA1 string `json:"sha1"`
		} `json:"manifest"`
		Version struct {
			Name string `json:"name"`
		} `json:"version"`
	}
	if err := GetJSON(ctx, runtimeIndex, &all); err != nil {
		return nil, fmt.Errorf("获取 Java 运行时列表失败：%w", err)
	}
	list := all[runtimePlatform()][component]
	if len(list) == 0 {
		return nil, fmt.Errorf("官方没有为此系统提供 %s", component)
	}
	var man struct {
		Files map[string]struct {
			Type       string `json:"type"`
			Executable bool   `json:"executable"`
			Downloads  struct {
				Raw *Download `json:"raw"`
			} `json:"downloads"`
		} `json:"files"`
	}
	if err := GetJSON(ctx, list[0].Manifest.URL, &man); err != nil {
		return nil, err
	}
	home := filepath.Join(dataDir, "runtime", component)
	var files []File
	var execs []string
	for name, f := range man.Files {
		p := filepath.Join(home, filepath.FromSlash(name))
		switch f.Type {
		case "directory":
			_ = os.MkdirAll(p, 0o755)
		case "file":
			if f.Downloads.Raw != nil {
				files = append(files, File{URL: f.Downloads.Raw.URL, Path: p, SHA1: f.Downloads.Raw.SHA1, Size: f.Downloads.Raw.Size})
				if f.Executable {
					execs = append(execs, p)
				}
			}
		}
	}
	if err := Batch(ctx, "Java "+list[0].Version.Name, files, threads, rep); err != nil {
		return nil, err
	}
	for _, p := range execs {
		_ = os.Chmod(p, 0o755)
	}
	// macOS bundles live under jre.bundle/Contents/Home
	for _, h := range []string{home, filepath.Join(home, "jre.bundle", "Contents", "Home")} {
		if j := probeJava(h); j != nil {
			j.Managed = true
			javaMu.Lock()
			javaCache = nil
			javaMu.Unlock()
			return j, nil
		}
	}
	return nil, fmt.Errorf("Java 安装后无法识别")
}

func itoa(n int) string { return strconv.Itoa(n) }

func componentMajor(c string) int {
	switch c {
	case "jre-legacy":
		return 8
	case "java-runtime-alpha":
		return 16
	case "java-runtime-beta", "java-runtime-gamma", "java-runtime-gamma-snapshot":
		return 17
	case "java-runtime-delta":
		return 21
	}
	return 25
}

// ComponentFor maps a Java major version to Mojang's runtime component.
func ComponentFor(major int) string {
	switch {
	case major <= 8:
		return "jre-legacy"
	case major <= 16:
		return "java-runtime-alpha"
	case major <= 17:
		return "java-runtime-gamma"
	case major <= 21:
		return "java-runtime-delta"
	}
	return "java-runtime-epsilon"
}

// StampMeta updates EririLily metadata of an instance.
func StampMeta(gameDir, id string, f func(*InstanceMeta)) { stampMeta(gameDir, id, f) }
