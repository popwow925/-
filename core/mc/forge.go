package mc

import (
	"archive/zip"
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Env carries what installers need beyond the game directory.
type Env struct {
	Cfg     Config
	DataDir string
	Rep     Reporter
	Stage   func(string)
}

func (e Env) stage(s string) {
	if e.Stage != nil {
		e.Stage(s)
	}
}

const (
	forgeMaven = "https://maven.minecraftforge.net/"
	neoMaven   = "https://maven.neoforged.net/releases/"
)

// ---------------------------------------------------------------- version lists

func forgeVersions(ctx context.Context, game string) ([]LoaderVersion, error) {
	var promos struct {
		Promos map[string]string `json:"promos"`
	}
	_ = GetJSON(ctx, "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json", &promos)
	rec := promos.Promos[game+"-recommended"]

	// Mirrors can serve a stale copy of maven-metadata.json that doesn't list
	// the newest Minecraft versions yet. When the game is missing from one source,
	// ask the next: mirror copy → BMCLAPI's own Forge index → the official file directly.
	metaURL := forgeMaven + "net/minecraftforge/forge/maven-metadata.json"
	var full []string
	var firstErr error
	var meta map[string][]string
	if err := GetJSON(ctx, metaURL, &meta); err == nil {
		full = meta[game]
	} else {
		firstErr = err
	}
	if len(full) == 0 {
		var bl []struct {
			Version string `json:"version"`
			Branch  string `json:"branch"`
		}
		if err := GetJSON(ctx, bmcl+"forge/minecraft/"+url.PathEscape(game), &bl); err == nil {
			for _, b := range bl {
				v := game + "-" + b.Version
				if b.Branch != "" {
					v += "-" + b.Branch
				}
				full = append(full, v)
			}
		} else if firstErr == nil {
			firstErr = err
		}
	}
	if len(full) == 0 {
		if b, err := getOnce(ctx, metaURL, 25*time.Second); err == nil {
			meta = nil
			if json.Unmarshal(b, &meta) == nil {
				full = meta[game]
			}
		} else if firstErr == nil {
			firstErr = err
		}
	}
	if len(full) == 0 && firstErr != nil {
		return nil, firstErr
	}
	sort.SliceStable(full, func(i, j int) bool { return verLess(forgePart(full[j], game), forgePart(full[i], game)) })
	var out []LoaderVersion
	for _, v := range full {
		out = append(out, LoaderVersion{Version: v, Stable: rec != "" && forgePart(v, game) == rec})
	}
	if rec == "" && len(out) > 0 {
		out[0].Stable = true
	}
	return out, nil
}

// forgePart: "1.20.1-47.3.0" -> "47.3.0"; "1.7.10-10.13.4.1614-1.7.10" -> "10.13.4.1614"
func forgePart(v, game string) string {
	v = strings.TrimPrefix(v, game+"-")
	return strings.TrimSuffix(v, "-"+game)
}

func verLess(a, b string) bool {
	pa, pb := strings.FieldsFunc(a, splitVer), strings.FieldsFunc(b, splitVer)
	for i := 0; i < len(pa) && i < len(pb); i++ {
		x, e1 := strconv.Atoi(pa[i])
		y, e2 := strconv.Atoi(pb[i])
		if e1 == nil && e2 == nil {
			if x != y {
				return x < y
			}
			continue
		}
		if pa[i] != pb[i] {
			return pa[i] < pb[i]
		}
	}
	return len(pa) < len(pb)
}

func splitVer(r rune) bool { return r == '.' || r == '-' || r == '+' }

type mavenMeta struct {
	Versions []string `xml:"versioning>versions>version"`
}

// NeoGame maps a NeoForge version to its Minecraft version:
// 21.1.77 -> 1.21.1, 20.4.237 -> 1.20.4, 21.0.x -> 1.21, 26.1.0.5 -> 26.1
func NeoGame(v string) string {
	p := strings.Split(strings.SplitN(v, "-", 2)[0], ".")
	if len(p) < 3 {
		return ""
	}
	major, _ := strconv.Atoi(p[0])
	if major >= 25 { // year-based Minecraft versions
		if len(p) >= 4 && p[2] != "0" {
			return p[0] + "." + p[1] + "." + p[2]
		}
		return p[0] + "." + p[1]
	}
	if p[1] == "0" {
		return "1." + p[0]
	}
	return "1." + p[0] + "." + p[1]
}

func neoVersions(ctx context.Context, game string) ([]LoaderVersion, error) {
	artifact := "neoforge"
	if game == "1.20.1" {
		artifact = "forge" // NeoForge for 1.20.1 still used the old coordinates
	}
	metaURL := neoMaven + "net/neoforged/" + artifact + "/maven-metadata.xml"
	pick := func(b []byte) []LoaderVersion {
		var m mavenMeta
		if xml.Unmarshal(b, &m) != nil {
			return nil
		}
		var out []LoaderVersion
		for _, v := range m.Versions {
			ok := false
			if artifact == "forge" {
				ok = strings.HasPrefix(v, "1.20.1-")
			} else {
				ok = NeoGame(v) == game
			}
			if ok {
				out = append(out, LoaderVersion{Version: v, Stable: !strings.Contains(v, "beta") && !strings.Contains(v, "alpha")})
			}
		}
		return out
	}
	b, err := GetBytes(ctx, metaURL)
	var out []LoaderVersion
	if err == nil {
		out = pick(b)
	}
	if len(out) == 0 { // a mirror's copy may predate this Minecraft version
		if b2, err2 := getOnce(ctx, metaURL, 25*time.Second); err2 == nil {
			out, err = pick(b2), nil
		} else if err == nil {
			err = err2
		}
	}
	if len(out) == 0 && err != nil {
		return nil, err
	}
	sort.SliceStable(out, func(i, j int) bool { return verLess(out[j].Version, out[i].Version) })
	return out, nil
}

func forgeInstallerURL(loader, game, v string) string {
	if loader == "neoforge" {
		if strings.HasPrefix(v, "1.20.1-") {
			return neoMaven + "net/neoforged/forge/" + v + "/forge-" + v + "-installer.jar"
		}
		return neoMaven + "net/neoforged/neoforge/" + v + "/neoforge-" + v + "-installer.jar"
	}
	return forgeMaven + "net/minecraftforge/forge/" + v + "/forge-" + v + "-installer.jar"
}

// ---------------------------------------------------------------- install

type forgeProfile struct {
	Spec       int                          `json:"spec"`
	JSON       string                       `json:"json"`
	Path       string                       `json:"path"`
	Minecraft  string                       `json:"minecraft"`
	Data       map[string]map[string]string `json:"data"`
	Libraries  []Library                    `json:"libraries"`
	Processors []struct {
		Sides     []string          `json:"sides"`
		Jar       string            `json:"jar"`
		Classpath []string          `json:"classpath"`
		Args      []string          `json:"args"`
		Outputs   map[string]string `json:"outputs"`
	} `json:"processors"`

	// legacy (1.12.2 and older)
	Install *struct {
		Path      string `json:"path"`
		FilePath  string `json:"filePath"`
		Minecraft string `json:"minecraft"`
	} `json:"install"`
	VersionInfo json.RawMessage `json:"versionInfo"`
}

// InstallForge installs Forge or NeoForge `ver` on top of vanilla `game` as instance `name`.
func InstallForge(ctx context.Context, env Env, game, loader, ver, name string) (string, error) {
	gameDir := env.Cfg.GameDir
	label := map[string]string{"forge": "Forge", "neoforge": "NeoForge"}[loader]
	if ver == "" {
		var vs []LoaderVersion
		var err error
		if loader == "forge" {
			vs, err = forgeVersions(ctx, game)
		} else {
			vs, err = neoVersions(ctx, game)
		}
		if err != nil || len(vs) == 0 {
			return "", fmt.Errorf("%s 不支持 Minecraft %s", label, game)
		}
		ver = vs[0].Version
		for _, v := range vs {
			if v.Stable {
				ver = v.Version
				break
			}
		}
	}
	if name == "" {
		name = game + "-" + label
	}
	if err := InstallVanilla(ctx, gameDir, game); err != nil {
		return "", err
	}
	// processors patch the vanilla client jar, so it must be present
	env.stage("下载 Minecraft " + game)
	if err := Complete(ctx, gameDir, game, env.Cfg.Threads, false, env.Rep); err != nil {
		return "", err
	}

	env.stage("下载 " + label + " 安装器")
	tmpDir := filepath.Join(gameDir, ".eririlily-tmp", fmt.Sprintf("%s-%s-%d", loader, ver, time.Now().UnixNano()))
	defer os.RemoveAll(tmpDir)
	inst := filepath.Join(tmpDir, "installer.jar")
	if err := Batch(ctx, label+" 安装器", []File{{URL: forgeInstallerURL(loader, game, ver), Path: inst}}, 1, env.Rep); err != nil {
		return "", fmt.Errorf("下载 %s 安装器失败：%w", label, err)
	}
	id, err := runForgeInstaller(ctx, env, inst, tmpDir, game, name)
	if err != nil {
		os.RemoveAll(VersionDir(gameDir, name))
		return "", err
	}
	stampMeta(gameDir, id, func(m *InstanceMeta) {
		m.Name, m.Game, m.Loader, m.LoaderVer = name, game, loader, forgePart(ver, game)
		if m.Created == 0 {
			m.Created = time.Now().Unix()
		}
	})
	return id, nil
}

func readZipFile(zr *zip.Reader, name string) ([]byte, error) {
	name = strings.TrimPrefix(name, "/")
	for _, f := range zr.File {
		if f.Name == name {
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			defer rc.Close()
			return io.ReadAll(rc)
		}
	}
	return nil, fmt.Errorf("安装器里缺少 %s", name)
}

// runForgeInstaller does what the official installer's "Install client" does.
func runForgeInstaller(ctx context.Context, env Env, installer, tmpDir, game, name string) (string, error) {
	gameDir := env.Cfg.GameDir
	libDir := filepath.Join(gameDir, "libraries")
	f, err := os.Open(installer)
	if err != nil {
		return "", err
	}
	defer f.Close()
	st, _ := f.Stat()
	zr, err := zip.NewReader(f, st.Size())
	if err != nil {
		return "", fmt.Errorf("安装器已损坏：%w", err)
	}
	raw, err := readZipFile(zr, "install_profile.json")
	if err != nil {
		return "", err
	}
	var prof forgeProfile
	if err := json.Unmarshal(raw, &prof); err != nil {
		return "", fmt.Errorf("安装配置损坏：%w", err)
	}

	// libraries bundled inside the installer (maven/...) go straight into libraries/
	for _, zf := range zr.File {
		if strings.HasPrefix(zf.Name, "maven/") && !zf.FileInfo().IsDir() {
			if dst, ok := within(libDir, strings.TrimPrefix(zf.Name, "maven/")); ok {
				if err := unzipOne(zf, dst); err != nil {
					return "", err
				}
			}
		}
	}

	if prof.Install != nil && len(prof.VersionInfo) > 0 {
		return legacyForge(ctx, env, zr, &prof, game, name)
	}

	// ---- modern (1.13+): version.json + processors
	vjson, err := readZipFile(zr, firstNonEmptyStr(prof.JSON, "/version.json"))
	if err != nil {
		return "", err
	}
	var v Version
	if err := json.Unmarshal(vjson, &v); err != nil {
		return "", err
	}
	v.ID, v.InheritsFrom = name, game
	if err := os.MkdirAll(VersionDir(gameDir, name), 0o755); err != nil {
		return "", err
	}
	if err := SaveVersion(gameDir, &v); err != nil {
		return "", err
	}

	// download version + installer libraries (entries without URL are bundled or produced below)
	files, _, _ := libraryFiles(gameDir, &Version{Libraries: append(append([]Library{}, v.Libraries...), prof.Libraries...)})
	env.stage("下载依赖库")
	if err := Batch(ctx, "依赖库", files, env.Cfg.Threads, env.Rep); err != nil {
		return "", err
	}
	if len(prof.Processors) == 0 {
		return name, nil
	}

	// ---- data table
	clientJar := filepath.Join(VersionDir(gameDir, game), game+".jar")
	data := map[string]string{
		"SIDE": "client", "MINECRAFT_JAR": clientJar, "MINECRAFT_VERSION": game, "ROOT": gameDir,
		"INSTALLER": installer, "LIBRARY_DIR": libDir,
	}
	for k, sides := range prof.Data {
		val := sides["client"]
		switch {
		case strings.HasPrefix(val, "[") && strings.HasSuffix(val, "]"):
			data[k] = filepath.Join(libDir, filepath.FromSlash(MavenPath(val[1:len(val)-1])))
		case strings.HasPrefix(val, "'") && strings.HasSuffix(val, "'"):
			data[k] = val[1 : len(val)-1]
		case strings.HasPrefix(val, "/"):
			b, err := readZipFile(zr, val)
			if err != nil {
				return "", err
			}
			p := filepath.Join(tmpDir, filepath.FromSlash(strings.TrimPrefix(val, "/")))
			_ = os.MkdirAll(filepath.Dir(p), 0o755)
			if err := os.WriteFile(p, b, 0o644); err != nil {
				return "", err
			}
			data[k] = p
		default:
			data[k] = val
		}
	}
	reTok := regexp.MustCompile(`\{([A-Z0-9_]+)\}`)
	sub := func(a string) string {
		if strings.HasPrefix(a, "[") && strings.HasSuffix(a, "]") {
			return filepath.Join(libDir, filepath.FromSlash(MavenPath(a[1:len(a)-1])))
		}
		return reTok.ReplaceAllStringFunc(a, func(m string) string {
			if v, ok := data[m[1:len(m)-1]]; ok {
				return v
			}
			return m
		})
	}
	lib := func(coords string) string { return filepath.Join(libDir, filepath.FromSlash(MavenPath(coords))) }

	java, err := ResolveJava(ctx, env.Cfg, env.DataDir, game, Hooks{Stage: env.Stage, Progress: env.Rep})
	if err != nil {
		return "", err
	}
	javaCLI := strings.Replace(java.Path, "javaw.exe", "java.exe", 1)

	var todo []int
	for i, p := range prof.Processors {
		if len(p.Sides) > 0 && !contains(p.Sides, "client") {
			continue
		}
		todo = append(todo, i)
	}
	for n, i := range todo {
		p := prof.Processors[i]
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		env.stage(fmt.Sprintf("安装处理 %d/%d", n+1, len(todo)))
		if env.Rep != nil {
			env.Rep(Progress{Stage: "安装处理", Done: n, Total: len(todo)})
		}
		// skip when every output is already present with the expected hash
		if len(p.Outputs) > 0 {
			ok := true
			for k, h := range p.Outputs {
				got, err := fileSHA1(sub(k))
				if err != nil || !strings.EqualFold(got, strings.Trim(sub(h), "'")) {
					ok = false
					break
				}
			}
			if ok {
				continue
			}
		}
		// Mojang's mappings: fetch them ourselves through the mirror instead of letting
		// the processor download from Mojang directly (slow or failing in mainland China)
		if i := indexOf(p.Args, "DOWNLOAD_MOJMAPS"); i >= 0 {
			if err := downloadMojmaps(ctx, env, gameDir, game, sub(argAfter(p.Args, "--side", "client")), sub(argAfter(p.Args, "--output", ""))); err == nil {
				continue
			} else {
				env.stage("镜像下载映射失败，改用官方安装流程：" + err.Error())
			}
		}
		jar := lib(p.Jar)
		main, err := jarMainClass(jar)
		if err != nil {
			return "", fmt.Errorf("处理器 %s：%w", p.Jar, err)
		}
		cp := []string{jar}
		for _, c := range p.Classpath {
			cp = append(cp, lib(c))
		}
		args := []string{"-cp", strings.Join(cp, string(os.PathListSeparator)), main}
		for _, a := range p.Args {
			args = append(args, sub(a))
		}
		cmd := exec.CommandContext(ctx, javaCLI, args...)
		cmd.Dir = tmpDir
		hideWindow(cmd)
		var out bytes.Buffer
		cmd.Stdout, cmd.Stderr = &out, &out
		err = cmd.Run()
		if err != nil && ctx.Err() == nil && retryable(out.String()) {
			// network hiccups inside a processor: one more try
			out.Reset()
			cmd = exec.CommandContext(ctx, javaCLI, args...)
			cmd.Dir = tmpDir
			hideWindow(cmd)
			cmd.Stdout, cmd.Stderr = &out, &out
			err = cmd.Run()
		}
		if err != nil {
			saveInstallLog(env.DataDir, p.Jar, args, out.String())
			tail := out.String()
			if len(tail) > 600 {
				tail = tail[len(tail)-600:]
			}
			return "", fmt.Errorf("安装处理失败（%s）：%v\n%s\n完整日志在启动器数据文件夹的 logs/forge-install.log", shortCoords(p.Jar), err, strings.TrimSpace(tail))
		}
		for k, h := range p.Outputs {
			got, err := fileSHA1(sub(k))
			if err != nil || !strings.EqualFold(got, strings.Trim(sub(h), "'")) {
				return "", fmt.Errorf("安装处理输出校验失败：%s", filepath.Base(sub(k)))
			}
		}
	}
	if env.Rep != nil {
		env.Rep(Progress{Stage: "安装处理", Done: len(todo), Total: len(todo)})
	}
	return name, nil
}

func indexOf(a []string, s string) int {
	for i, x := range a {
		if x == s {
			return i
		}
	}
	return -1
}

func argAfter(a []string, flag, def string) string {
	if i := indexOf(a, flag); i >= 0 && i+1 < len(a) {
		return a[i+1]
	}
	return def
}

// downloadMojmaps puts the official obfuscation mappings where the next
// processors expect them (what installertools' DOWNLOAD_MOJMAPS task does).
func downloadMojmaps(ctx context.Context, env Env, gameDir, game, side, out string) error {
	if out == "" || strings.Contains(out, "{") {
		return fmt.Errorf("缺少输出路径")
	}
	v, err := LoadVersion(gameDir, game)
	if err != nil {
		return err
	}
	d := v.Downloads[side+"_mappings"]
	if d == nil || d.URL == "" {
		return fmt.Errorf("版本信息里没有 %s 映射", side)
	}
	env.stage("下载 Mojang 映射")
	return Batch(ctx, "Mojang 映射", []File{{URL: d.URL, Path: out, SHA1: d.SHA1, Size: d.Size}}, 1, env.Rep)
}

func retryable(log string) bool {
	l := strings.ToLower(log)
	for _, k := range []string{"timed out", "timeout", "connection reset", "unknownhost", "connectexception", "sslhandshake", "socketexception", "failed to download"} {
		if strings.Contains(l, k) {
			return true
		}
	}
	return false
}

// saveInstallLog keeps the full output of a failed processor for diagnosis.
func saveInstallLog(dataDir, jar string, args []string, out string) {
	if dataDir == "" {
		return
	}
	dir := filepath.Join(dataDir, "logs")
	_ = os.MkdirAll(dir, 0o755)
	body := "processor: " + jar + "\nargs: " + strings.Join(args, " ") + "\n\n" + out
	_ = os.WriteFile(filepath.Join(dir, "forge-install.log"), []byte(body), 0o644)
}

func shortCoords(c string) string {
	p := strings.Split(c, ":")
	if len(p) >= 2 {
		return p[1]
	}
	return c
}

func contains(a []string, s string) bool {
	for _, x := range a {
		if x == s {
			return true
		}
	}
	return false
}

func firstNonEmptyStr(s ...string) string {
	for _, x := range s {
		if x != "" {
			return x
		}
	}
	return ""
}

func jarMainClass(path string) (string, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return "", fmt.Errorf("无法打开 %s", filepath.Base(path))
	}
	defer zr.Close()
	b, err := readZipFile(&zr.Reader, "META-INF/MANIFEST.MF")
	if err != nil {
		return "", err
	}
	sc := bufio.NewScanner(bytes.NewReader(b))
	for sc.Scan() {
		if k, v, ok := strings.Cut(sc.Text(), ":"); ok && strings.TrimSpace(k) == "Main-Class" {
			return strings.TrimSpace(v), nil
		}
	}
	return "", fmt.Errorf("%s 没有 Main-Class", filepath.Base(path))
}

// legacyForge handles installers for Minecraft 1.12.2 and older.
func legacyForge(ctx context.Context, env Env, zr *zip.Reader, prof *forgeProfile, game, name string) (string, error) {
	gameDir := env.Cfg.GameDir
	// the universal jar sits at the installer root
	if prof.Install.FilePath != "" && prof.Install.Path != "" {
		b, err := readZipFile(zr, prof.Install.FilePath)
		if err != nil {
			return "", err
		}
		dst := filepath.Join(gameDir, "libraries", filepath.FromSlash(MavenPath(prof.Install.Path)))
		_ = os.MkdirAll(filepath.Dir(dst), 0o755)
		if err := os.WriteFile(dst, b, 0o644); err != nil {
			return "", err
		}
	}
	// keep client libraries only, and modernise old http maven URLs
	var vi map[string]json.RawMessage
	if err := json.Unmarshal(prof.VersionInfo, &vi); err != nil {
		return "", err
	}
	var libs []map[string]any
	_ = json.Unmarshal(vi["libraries"], &libs)
	var keep []map[string]any
	for _, l := range libs {
		if cr, ok := l["clientreq"].(bool); ok && !cr {
			continue
		}
		if u, ok := l["url"].(string); ok {
			l["url"] = fixLegacyMaven(u)
		}
		delete(l, "checksums")
		delete(l, "serverreq")
		delete(l, "clientreq")
		keep = append(keep, l)
	}
	vi["libraries"], _ = json.Marshal(keep)
	b, _ := json.Marshal(vi)
	var v Version
	if err := json.Unmarshal(b, &v); err != nil {
		return "", err
	}
	v.ID, v.InheritsFrom, v.Jar = name, game, ""
	if err := os.MkdirAll(VersionDir(gameDir, name), 0o755); err != nil {
		return "", err
	}
	if err := SaveVersion(gameDir, &v); err != nil {
		return "", err
	}
	files, _, _ := libraryFiles(gameDir, &v)
	env.stage("下载依赖库")
	return name, Batch(ctx, "依赖库", files, env.Cfg.Threads, env.Rep)
}

func fixLegacyMaven(u string) string {
	switch {
	case strings.Contains(u, "files.minecraftforge.net/maven"):
		return forgeMaven
	case strings.Contains(u, "repo.maven.apache.org"), strings.Contains(u, "repo1.maven.org"):
		return "https://repo1.maven.org/maven2/"
	}
	return strings.Replace(u, "http://", "https://", 1)
}

// isForgeLike reports whether a merged version boots through Forge/NeoForge.
func isForgeLike(v *Version) bool {
	m := v.MainClass
	return strings.Contains(m, "cpw.mods") || strings.Contains(m, "minecraftforge") || strings.Contains(m, "neoforged") ||
		strings.Contains(m, "launchwrapper")
}
