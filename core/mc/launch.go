package mc

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
)

type LaunchOptions struct {
	GameDir    string
	ID         string
	Java       *Java
	Account    Account
	MemoryMB   int
	ExtraJVM   string
	Width      int
	Height     int
	Fullscreen bool
	Isolation  bool
	Server     string // "host[:port]": join this server right after start
}

// RunDir is where saves/mods/config live for an instance.
func RunDir(gameDir, id string, isolation bool) string {
	if isolation {
		return VersionDir(gameDir, id)
	}
	return gameDir
}

func hasLoader(v *Version) bool {
	return strings.Contains(v.MainClass, "fabric") || strings.Contains(v.MainClass, "quilt") ||
		strings.Contains(v.MainClass, "forge") || strings.Contains(v.MainClass, "launchwrapper") ||
		strings.Contains(v.MainClass, "bootstraplauncher") || strings.Contains(v.MainClass, "neoforged")
}

// AutoMemory picks -Xmx from physical memory.
func AutoMemory(modded bool) int {
	total := TotalMemoryMB()
	if total <= 0 {
		return 2048
	}
	if Android {
		// the phone needs its memory too: Android kills a game that takes too much
		pct, lo, hi := 30, 1024, 3072
		if modded {
			pct, lo, hi = 40, 1536, 4096
		}
		m := total * pct / 100
		m = max(lo, min(m, hi, total-2560))
		return m / 128 * 128
	}
	m := total / 4
	if modded {
		m = total / 3
		if m < 4096 {
			m = 4096
		}
	}
	if m < 2048 {
		m = 2048
	}
	if m > 8192 {
		m = 8192
	}
	if limit := total - 1536; m > limit && limit >= 1024 {
		m = limit
	}
	return m
}

var rePlaceholder = regexp.MustCompile(`\$\{([a-zA-Z_]+)\}`)

// GameArgs is a resolved launch: JVM options, main class and game arguments.
type GameArgs struct {
	JVM    []string
	Main   string
	Game   []string
	RunDir string
	Ver    *Version
}

// BuildCommand assembles the java command line for an instance.
func BuildCommand(o LaunchOptions) (*exec.Cmd, string, error) {
	ga, err := BuildArgs(o)
	if err != nil {
		return nil, "", err
	}
	args := append(append(append([]string{}, ga.JVM...), ga.Main), ga.Game...)
	cmd := exec.Command(o.Java.Path, args...)
	cmd.Dir = ga.RunDir
	hideWindow(cmd)
	return cmd, ga.RunDir, nil
}

// BuildArgs resolves everything needed to start an instance.
func BuildArgs(o LaunchOptions) (*GameArgs, error) {
	v, err := Resolve(o.GameDir, o.ID)
	if err != nil {
		return nil, err
	}
	if v.MainClass == "" {
		return nil, fmt.Errorf("版本 %s 缺少主类，文件可能损坏", o.ID)
	}
	if o.Java == nil {
		return nil, fmt.Errorf("没有可用的 Java")
	}
	runDir := RunDir(o.GameDir, o.ID, o.Isolation)
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		return nil, err
	}
	_, cp, natives := libraryFiles(o.GameDir, v)
	clientJar := filepath.Join(VersionDir(o.GameDir, v.Jar), v.Jar+".jar")
	if _, err := os.Stat(clientJar); err != nil {
		return nil, fmt.Errorf("缺少游戏本体 %s.jar，请补全文件", v.Jar)
	}
	// Forge/NeoForge expect the game jar under the instance's own name (their
	// ignoreList refers to ${version_name}.jar), like the official launcher does.
	if v.Jar != o.ID && isForgeLike(v) {
		own := filepath.Join(VersionDir(o.GameDir, o.ID), o.ID+".jar")
		if st, err := os.Stat(own); err != nil || st.Size() != fileSize(clientJar) {
			if err := copyFile(clientJar, own); err != nil {
				return nil, err
			}
		}
		clientJar = own
	}
	cp = append(cp, clientJar)
	nativesDir := filepath.Join(VersionDir(o.GameDir, o.ID), "natives-"+osName+"-"+archName())
	if !Android { // Android uses its own LWJGL natives
		if err := extractNatives(natives, nativesDir); err != nil {
			return nil, err
		}
	}
	assetsRoot := filepath.Join(o.GameDir, "assets")
	virtual := prepareLegacyAssets(o.GameDir, runDir, v)
	if virtual == "" {
		virtual = assetsRoot
	}
	userType := "msa"
	token := o.Account.Token
	if o.Account.Type == "offline" {
		userType = "legacy"
		token = "0"
	}
	vars := map[string]string{
		"auth_player_name":    o.Account.Name,
		"version_name":        o.ID,
		"game_directory":      runDir,
		"assets_root":         assetsRoot,
		"game_assets":         virtual,
		"assets_index_name":   assetIndexID(v),
		"auth_uuid":           o.Account.ID,
		"auth_access_token":   token,
		"auth_session":        token,
		"auth_xuid":           o.Account.XUID,
		"clientid":            "",
		"user_type":           userType,
		"user_properties":     "{}",
		"version_type":        AppName,
		"natives_directory":   nativesDir,
		"launcher_name":       AppName,
		"launcher_version":    AppVersion,
		"classpath":           strings.Join(cp, string(os.PathListSeparator)),
		"classpath_separator": string(os.PathListSeparator),
		"library_directory":   filepath.Join(o.GameDir, "libraries"),
		"resolution_width":    fmt.Sprint(o.Width),
		"resolution_height":   fmt.Sprint(o.Height),
	}
	sub := func(s string) string {
		return rePlaceholder.ReplaceAllStringFunc(s, func(m string) string {
			k := m[2 : len(m)-1]
			if val, ok := vars[k]; ok {
				return val
			}
			return m
		})
	}
	features := map[string]bool{"has_custom_resolution": o.Width > 0 && o.Height > 0}

	mem := o.MemoryMB
	if mem <= 0 {
		mem = AutoMemory(hasLoader(v))
	}
	// heap regions sized for the heap: 16M regions waste memory on a phone-sized heap
	region := "-XX:G1HeapRegionSize=16M"
	if mem < 4096 {
		region = "-XX:G1HeapRegionSize=4M"
	}
	var args []string
	args = append(args,
		fmt.Sprintf("-Xmx%dm", mem),
		"-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=20",
		"-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", region,
		"-XX:-OmitStackTraceInFastThrow", "-XX:-DontCompileHugeMethods",
		"-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
		"-Dlog4j2.formatMsgNoLookups=true",
		"-Dfml.ignoreInvalidMinecraftCertificates=true", "-Dfml.ignorePatchDiscrepancies=true",
		"-Dminecraft.launcher.brand="+AppName, "-Dminecraft.launcher.version="+AppVersion,
	)
	if o.ExtraJVM != "" {
		args = append(args, strings.Fields(o.ExtraJVM)...)
	}
	if v.Logging != nil && v.Logging.Client != nil && v.Logging.Client.Argument != "" {
		p := filepath.Join(o.GameDir, "assets", "log_configs", v.Logging.Client.File.ID)
		if _, err := os.Stat(p); err == nil {
			args = append(args, strings.ReplaceAll(v.Logging.Client.Argument, "${path}", p))
		}
	}
	if v.Arguments != nil && len(v.Arguments.JVM) > 0 {
		for _, a := range v.Arguments.JVM {
			if ruleAllows(a.Rules, features) {
				for _, s := range a.Value {
					args = append(args, sub(s))
				}
			}
		}
	} else {
		if runtime.GOOS == "windows" {
			args = append(args, "-XX:HeapDumpPath=MojangTricksIntelFixesHeapDumpPath.exe_minecraft.exe.heapdump")
		}
		args = append(args, "-Djava.library.path="+nativesDir, "-cp", vars["classpath"])
	}
	nJVM := len(args)
	args = append(args, v.MainClass)
	if v.Arguments != nil && len(v.Arguments.Game) > 0 {
		for _, a := range v.Arguments.Game {
			if len(a.Rules) > 0 {
				continue // demo / custom resolution / quick play: handled below
			}
			for _, s := range a.Value {
				args = append(args, sub(s))
			}
		}
	}
	if v.MinecraftArguments != "" {
		for _, s := range strings.Fields(v.MinecraftArguments) {
			args = append(args, sub(s))
		}
	}
	if o.Width > 0 && o.Height > 0 {
		args = append(args, "--width", fmt.Sprint(o.Width), "--height", fmt.Sprint(o.Height))
	}
	if o.Fullscreen {
		args = append(args, "--fullscreen")
	}
	if o.Server != "" {
		args = append(args, serverArgs(GameVersion(o.GameDir, o.ID), o.Server)...)
	}
	jvm := dedupeJVM(append(append([]string{}, args[:nJVM]...), v.MainClass))
	jvm = jvm[:len(jvm)-1]
	return &GameArgs{JVM: jvm, Main: v.MainClass, Game: args[nJVM+1:], RunDir: runDir, Ver: v}, nil
}

// dedupeJVM drops our default -X / -XX flags when the version json sets the same one.
func dedupeJVM(args []string) []string {
	last := map[string]int{}
	key := func(a string) string {
		switch {
		case strings.HasPrefix(a, "-Xmx"), strings.HasPrefix(a, "-Xms"), strings.HasPrefix(a, "-Xss"):
			return a[:4]
		case strings.HasPrefix(a, "-XX:+"), strings.HasPrefix(a, "-XX:-"):
			return "-XX:" + strings.SplitN(a[5:], "=", 2)[0]
		case strings.HasPrefix(a, "-XX:"):
			return "-XX:" + strings.SplitN(a[4:], "=", 2)[0]
		case strings.HasPrefix(a, "-D"):
			return strings.SplitN(a, "=", 2)[0]
		}
		return ""
	}
	end := len(args)
	for i, a := range args {
		if !strings.HasPrefix(a, "-") || a == "-cp" || a == "-classpath" {
			if a == "-cp" || a == "-classpath" {
				continue
			}
			if i > 0 && (args[i-1] == "-cp" || args[i-1] == "-classpath") {
				continue
			}
			end = i // main class reached
			break
		}
		if k := key(a); k != "" {
			last[k] = i
		}
	}
	var out []string
	for i, a := range args {
		if i < end {
			if k := key(a); k != "" && last[k] != i {
				continue
			}
		}
		out = append(out, a)
	}
	return out
}

func fileSize(p string) int64 {
	st, err := os.Stat(p)
	if err != nil {
		return -1
	}
	return st.Size()
}

var reServer = regexp.MustCompile(`^[A-Za-z0-9.\-]{1,253}(:\d{1,5})?$`)

// serverArgs: 1.20+ uses Quick Play; older versions take --server/--port.
func serverArgs(game, addr string) []string {
	if !reServer.MatchString(addr) {
		return nil
	}
	if quickPlay(game) {
		return []string{"--quickPlayMultiplayer", addr}
	}
	host, port := addr, "25565"
	if i := strings.LastIndex(addr, ":"); i > 0 {
		host, port = addr[:i], addr[i+1:]
	}
	return []string{"--server", host, "--port", port}
}

func quickPlay(game string) bool {
	p := strings.Split(game, ".")
	if len(p) >= 2 && p[0] == "1" {
		n, _ := strconv.Atoi(p[1])
		return n >= 20
	}
	return len(p) >= 2 && p[0] >= "2" // 26.x and later
}
