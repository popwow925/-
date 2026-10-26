package mc

import (
	"bufio"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// AndroidSpec is handed to the app's game process. Field names are part of
// the contract with the Kotlin side (GameSpec.kt).
type AndroidSpec struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	Game      string            `json:"game"`
	Loader    string            `json:"loader"`
	RunDir    string            `json:"runDir"`
	JavaHome  string            `json:"javaHome"`
	JavaMajor int               `json:"javaMajor"`
	LibJVM    string            `json:"libJvm"`
	JVMArgs   []string          `json:"jvmArgs"`
	Classpath []string          `json:"classpath"`
	MainClass string            `json:"mainClass"`
	GameArgs  []string          `json:"gameArgs"`
	LibPath   []string          `json:"libPath"` // java.library.path
	Renderer  Renderer          `json:"renderer"`
	Env       map[string]string `json:"env"`
	LogFile   string            `json:"logFile"`
	ExitFile  string            `json:"exitFile"`
	PidFile   string            `json:"pidFile"`
	Settings  GameSettings      `json:"settings"`
	Account   string            `json:"account"`
}

// GameSettings are the in-game options of the Android shell.
type GameSettings struct {
	RenderScale     int    `json:"renderScale"`
	FpsLimit        int    `json:"fpsLimit"`
	Vsync           bool   `json:"vsync"`
	PerfHud         string `json:"perfHud"`
	SustainedPerf   bool   `json:"sustainedPerf"`
	BigCores        bool   `json:"bigCores"`
	PerfHint        bool   `json:"perfHint"`
	GyroAim         bool   `json:"gyroAim"`
	GyroSens        int    `json:"gyroSens"`
	MouseSpeed      int    `json:"mouseSpeed"`
	ControlsOpacity int    `json:"controlsOpacity"`
	ControlsLayout  string `json:"controlsLayout"`
	Haptics         bool   `json:"haptics"`
	DynamicRes      bool   `json:"dynamicRes"`
	GuiScale        int    `json:"guiScale"` // from options.txt, 0 = auto
}

func settingsOf(c Config) GameSettings {
	return GameSettings{RenderScale: c.RenderScale, FpsLimit: c.FpsLimit, Vsync: c.Vsync, PerfHud: c.PerfHud,
		SustainedPerf: c.SustainedPerf, BigCores: c.BigCores, PerfHint: c.PerfHint, GyroAim: c.GyroAim, GyroSens: c.GyroSens,
		MouseSpeed: c.MouseSpeed, ControlsOpacity: c.ControlsOpacity, ControlsLayout: c.ControlsLayout, Haptics: c.Haptics,
		DynamicRes: c.DynamicRes}
}

// AndroidStart is called with the spec path once it is written; the app
// listens for it on the core's stdout and opens the game.
var AndroidStart func(specPath string)

func isLWJGL(p string) bool {
	q := filepath.ToSlash(p)
	return strings.Contains(q, "/org/lwjgl/") || strings.HasPrefix(filepath.Base(q), "lwjgl")
}

// rewriteCP swaps the desktop LWJGL jars of a class path for the Android ones.
func rewriteCP(list []string, ours []string) []string {
	out := append([]string{}, ours...)
	for _, p := range list {
		if p != "" && !isLWJGL(p) {
			out = append(out, p)
		}
	}
	return out
}

// buildAndroidSpec turns a desktop launch into an in-process launch on Android.
func buildAndroidSpec(cfg Config, dataDir string, req LaunchRequest, ga *GameArgs, java *Java) (*AndroidSpec, error) {
	if usesLWJGL2(ga.Ver) {
		return nil, errors.New("1.12.2 及更早的版本使用 LWJGL 2，安卓版暂不支持，请使用 1.13 及以上版本")
	}
	jars, err := EnsureLWJGL(dataDir)
	if err != nil {
		return nil, err
	}
	game := GameVersion(cfg.GameDir, req.ID)
	rend, err := PickRenderer(dataDir, cfg.Renderer, game)
	if err != nil {
		return nil, err
	}
	dir := filepath.Join(dataDir, "launch", time.Now().Format("20060102-150405"))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	cleanLaunchDirs(filepath.Join(dataDir, "launch"), 6)

	sp := &AndroidSpec{ID: req.ID, Name: req.ID, Game: game, Loader: DetectLoader(ga.Ver), RunDir: ga.RunDir,
		JavaHome: java.Home, JavaMajor: java.Major, LibJVM: jvmLib(java.Home), MainClass: ga.Main, Renderer: rend,
		LogFile: filepath.Join(dir, "game.log"), ExitFile: filepath.Join(dir, "exit"), PidFile: filepath.Join(dir, "pid"),
		Settings: settingsOf(cfg), Account: req.Account.Name, Env: map[string]string{}}
	if in, err := Info(cfg.GameDir, req.ID); err == nil && in.EL != nil && in.EL.Name != "" {
		sp.Name = in.EL.Name
	}
	sp.Settings.GuiScale = optionInt(filepath.Join(ga.RunDir, "options.txt"), "guiScale")
	for k, v := range rend.Env {
		sp.Env[k] = v
	}
	cache := AEnv.CacheDir
	if cache == "" {
		cache = filepath.Join(dataDir, "cache")
	}
	tmp := filepath.Join(cache, "jvm-tmp")
	_ = os.MkdirAll(tmp, 0o755)

	// java.library.path: our natives first (bridge, LWJGL, OpenAL), then the JRE
	sp.LibPath = []string{AEnv.NativeDir}
	for _, d := range []string{"lib", "lib/" + jreArch(), "lib/" + jreArch() + "/jli", "lib/jli", "lib/server", "lib/" + jreArch() + "/server"} {
		if st, err := os.Stat(filepath.Join(java.Home, filepath.FromSlash(d))); err == nil && st.IsDir() {
			sp.LibPath = append(sp.LibPath, filepath.Join(java.Home, filepath.FromSlash(d)))
		}
	}

	var jvm []string
	args := ga.JVM
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "-cp" || a == "-classpath" || a == "--class-path":
			if i+1 < len(args) {
				sp.Classpath = rewriteCP(strings.Split(args[i+1], ":"), jars)
				i++
			}
			continue
		case strings.HasPrefix(a, "-Djava.library.path="),
			strings.HasPrefix(a, "-XX:HeapDumpPath="),
			strings.HasPrefix(a, "-Djna.tmpdir="),
			strings.HasPrefix(a, "-Dorg.lwjgl.system.SharedLibraryExtractPath="),
			strings.HasPrefix(a, "-Dio.netty.native.workdir="):
			continue
		case strings.HasPrefix(a, "-D") && strings.Contains(a, "=") && strings.Contains(a, "/org/lwjgl/"):
			// e.g. Forge's -DlegacyClassPath=…: same swap as the class path
			k, v, _ := strings.Cut(a, "=")
			a = k + "=" + strings.Join(rewriteCP(strings.Split(v, ":"), jars), ":")
		}
		jvm = append(jvm, a)
	}
	if sp.Classpath == nil {
		sp.Classpath = jars
	}
	glfw := filepath.Join(AEnv.NativeDir, "libelbridge.so")
	jvm = append(jvm,
		"-Djava.io.tmpdir="+tmp,
		"-Duser.home="+filepath.Dir(cfg.GameDir),
		"-Djava.awt.headless=true",
		"-Dorg.lwjgl.librarypath="+AEnv.NativeDir,
		"-Dorg.lwjgl.glfw.libname="+glfw,
		"-Dorg.lwjgl.opengl.libname="+rend.Lib,
		"-Dorg.lwjgl.opengles.libname=libGLESv3.so",
		"-Dorg.lwjgl.egl.libname=libEGL.so",
		"-Dorg.lwjgl.openal.libname="+filepath.Join(AEnv.NativeDir, "libopenal.so"),
		"-Dorg.lwjgl.vulkan.libname=libvulkan.so",
		"-Dorg.lwjgl.system.allocator=system",
		"-Dorg.lwjgl.util.NoChecks=true",
		"-Dorg.lwjgl.system.SharedLibraryExtractPath="+tmp,
		"-Djna.tmpdir="+tmp,
		"-Dio.netty.native.workdir="+tmp,
		"-Dfml.earlyprogresswindow=false",         // Forge's early window opens its own GLFW context
		"-Dfml.earlyWindowControl=false",          // NeoForge
		"-Dloader.disable_forked_guis=true",       // Fabric's error GUI runs a second JVM
		"-Dsodium.checks.issue2561=false",         // Sodium refuses unknown GL drivers otherwise
		"-Dnet.minecraft.clientmodname=EririLily", //
		"-Dsun.java.command="+ga.Main,             // some mods read it
		"-Dfile.encoding=UTF-8",
		"-XX:+UseStringDeduplication",
		"-XX:ReservedCodeCacheSize=128m",
		"-XX:+ParallelRefProcEnabled",
		"-XX:+DisableExplicitGC",
	)
	if java.Major <= 8 {
		jvm = dropJava9(jvm)
	}
	sp.JVMArgs = dedupeJVM(append(jvm, ga.Main))
	sp.JVMArgs = sp.JVMArgs[:len(sp.JVMArgs)-1]
	sp.GameArgs = ga.Game
	if err := writeJSON(filepath.Join(dir, "spec.json"), sp); err != nil {
		return nil, err
	}
	return sp, nil
}

// dropJava9 removes options Java 8 rejects at start.
func dropJava9(args []string) []string {
	var out []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		if strings.HasPrefix(a, "--add-") || strings.HasPrefix(a, "--enable-native-access") || a == "-p" || a == "--module-path" ||
			strings.HasPrefix(a, "-Dstdout.encoding") || strings.HasPrefix(a, "-Dstderr.encoding") || a == "-XX:+UseStringDeduplication" {
			if (a == "-p" || a == "--module-path" || !strings.Contains(a, "=")) && strings.HasPrefix(a, "-") && i+1 < len(args) && !strings.HasPrefix(args[i+1], "-") {
				i++
			}
			continue
		}
		out = append(out, a)
	}
	return out
}

func cleanLaunchDirs(root string, keep int) {
	ents, _ := os.ReadDir(root)
	var names []string
	for _, e := range ents {
		if e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	for len(names) > keep {
		_ = os.RemoveAll(filepath.Join(root, names[0]))
		names = names[1:]
	}
}

// optionInt reads an integer from options.txt (0 when missing).
func optionInt(path, key string) int {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	for _, l := range strings.Split(string(b), "\n") {
		if k, v, ok := strings.Cut(strings.TrimSpace(l), ":"); ok && k == key {
			n, _ := strconv.Atoi(strings.TrimSpace(v))
			return n
		}
	}
	return 0
}

// startAndroid waits for the app's game process and follows it through the
// files it writes: its pid, its output and finally its exit code.
func startAndroid(sp *AndroidSpec, g *Game, h Hooks, finish func(code int)) {
	specPath := filepath.Join(filepath.Dir(sp.LogFile), "spec.json")
	if AndroidStart != nil {
		AndroidStart(specPath)
	}
	if h.Log != nil {
		h.Log("[EririLily] 渲染器 " + sp.Renderer.Name + " · Java " + strconv.Itoa(sp.JavaMajor))
	}
	go func() {
		var readyOnce sync.Once
		var off int64
		var partial string
		deadline := time.Now().Add(90 * time.Second)
		gone := time.Time{}
		for {
			// output
			if f, err := os.Open(sp.LogFile); err == nil {
				if _, err := f.Seek(off, io.SeekStart); err == nil {
					r := bufio.NewReaderSize(f, 64<<10)
					for {
						line, err := r.ReadString('\n')
						off += int64(len(line))
						if err != nil {
							partial += line
							break
						}
						line = strings.TrimRight(partial+line, "\r\n")
						partial = ""
						g.addLine(line)
						if h.Log != nil {
							h.Log(line)
						}
						if h.Ready != nil && isReadyLine(line) {
							readyOnce.Do(h.Ready)
						}
					}
				}
				f.Close()
			}
			// exit
			if b, err := os.ReadFile(sp.ExitFile); err == nil {
				code, _ := strconv.Atoi(strings.TrimSpace(string(b)))
				finish(code)
				return
			}
			pid := g.pid
			if pid == 0 {
				if b, err := os.ReadFile(sp.PidFile); err == nil {
					pid, _ = strconv.Atoi(strings.TrimSpace(string(b)))
					g.mu.Lock()
					g.pid = pid
					g.mu.Unlock()
				} else if time.Now().After(deadline) || g.killed {
					g.addLine("[EririLily] 游戏进程没有启动")
					finish(-1)
					return
				}
			} else if _, err := os.Stat("/proc/" + strconv.Itoa(pid)); err != nil {
				// the process is gone without writing an exit code: killed (memory) or crashed natively
				if gone.IsZero() {
					gone = time.Now()
				} else if time.Since(gone) > 1500*time.Millisecond {
					g.addLine("[EririLily] 游戏进程被系统结束（可能是内存不足或渲染器崩溃）")
					finish(-9)
					return
				}
			}
			time.Sleep(150 * time.Millisecond)
		}
	}()
}

func isReadyLine(line string) bool {
	return strings.Contains(line, "Backend library: LWJGL") || strings.Contains(line, "LWJGL Version") ||
		strings.Contains(line, "Reloading ResourceManager") || strings.Contains(line, "OpenAL initialized") ||
		strings.Contains(line, "Created: ") && strings.Contains(line, "textures-atlas")
}

func killPid(pid int) {
	if pid <= 0 {
		return
	}
	if p, err := os.FindProcess(pid); err == nil {
		_ = p.Kill()
	}
}

// MergeGameSettings folds in what the player changed in the in-game Control
// Centre (the game process writes game_settings.json; the core owns config.json).
func MergeGameSettings(dataDir string, s *Store) {
	p := filepath.Join(dataDir, "game_settings.json")
	var g struct {
		GameSettings
		Time int64 `json:"time"`
	}
	if readJSON(p, &g) != nil || g.Time == 0 {
		return
	}
	stamp := filepath.Join(dataDir, ".game_settings_merged")
	if b, err := os.ReadFile(stamp); err == nil && strings.TrimSpace(string(b)) == strconv.FormatInt(g.Time, 10) {
		return
	}
	s.Update(func(c *Config) {
		c.RenderScale, c.FpsLimit, c.Vsync, c.PerfHud = g.RenderScale, g.FpsLimit, g.Vsync, g.PerfHud
		c.SustainedPerf, c.BigCores, c.PerfHint, c.GyroAim = g.SustainedPerf, g.BigCores, g.PerfHint, g.GyroAim
		c.GyroSens, c.MouseSpeed, c.ControlsOpacity, c.Haptics, c.DynamicRes = g.GyroSens, g.MouseSpeed, g.ControlsOpacity, g.Haptics, g.DynamicRes
		if g.ControlsLayout != "" {
			c.ControlsLayout = g.ControlsLayout
		}
	})
	_ = os.WriteFile(stamp, []byte(strconv.FormatInt(g.Time, 10)), 0o644)
}
