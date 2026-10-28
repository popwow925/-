package mc

// The game runtime on Android: the app runs Minecraft on Zalith Launcher 2's native runtime and
// renderers (bundled in the APK, unpacked by the app). The core still does everything else —
// versions, downloads, loaders, accounts, arguments — and hands the app a launch spec.

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ZRuntime is set by the app (ERIRILILY_RUNTIME=zalith): Java, LWJGL and renderers come from the
// APK's runtime module, not from the core.
var ZRuntime = os.Getenv("ERIRILILY_RUNTIME") == "zalith"

// zJavaDir is where the app unpacks its bundled Java runtimes (jre-8, jre-17, …).
func zJavaDir() string { return os.Getenv("ERIRILILY_JAVA_DIR") }

// zBundledJava lists the Java majors the APK ships ("8,17,21,25").
func zBundledJava() []int {
	var out []int
	for _, s := range strings.Split(os.Getenv("ERIRILILY_BUNDLED_JAVA"), ",") {
		if n, err := strconv.Atoi(strings.TrimSpace(s)); err == nil && n > 0 {
			out = append(out, n)
		}
	}
	if len(out) == 0 {
		out = []int{8, 17, 21, 25}
	}
	return out
}

// zJavas describes the bundled runtimes. They may not be unpacked yet: the app does that right
// before the game starts, so nothing here touches the disk.
func zJavas() []*Java {
	var out []*Java
	all := zBundledJava()
	for i := len(all) - 1; i >= 0; i-- {
		n := all[i]
		out = append(out, &Java{Home: filepath.Join(zJavaDir(), "jre-"+strconv.Itoa(n)), Major: n,
			Version: strconv.Itoa(n) + " (内置)", Bits64: true, Managed: true})
	}
	return out
}

// zPickJava: the smallest bundled Java that is new enough (1.16 and older stay on 8/17).
func zPickJava(major int) *Java {
	var best *Java
	for _, j := range zJavas() {
		if j.Major < major || (major <= 8 && j.Major > 17) {
			continue
		}
		if best == nil || j.Major < best.Major {
			best = j
		}
	}
	if best == nil {
		all := zJavas()
		best = all[0]
	}
	return best
}

// zRenderers mirrors the app's renderer table (cn.eririlily.runtime.Renderers).
func zRenderers() []Renderer {
	gpu := strings.ToLower(AEnv.Device.GLRenderer)
	list := []Renderer{
		{ID: "krypton", Name: "Krypton Wrapper", Desc: "默认 · 兼容性最好，适合大多数手机", Lib: "libng_gl4es.so", GLES: 3},
		{ID: "gl4es", Name: "GL4ES", Desc: "OpenGL 2.1 · 老版本最稳", Lib: "libgl4es_114.so", GLES: 2, MaxGame: "1.21.4"},
		{ID: "zink", Name: "Kopper Zink", Desc: "OpenGL over Vulkan · 新版本画质更好，部分机型更快", Lib: "libglxshim.so", GLES: 3},
		{ID: "virgl", Name: "VirGL", Desc: "Mesa VirGL", Lib: "libOSMesa_2121.so", GLES: 3},
	}
	if gpu == "" || strings.Contains(gpu, "adreno") {
		list = append(list, Renderer{ID: "freedreno", Name: "Freedreno", Desc: "骁龙 Adreno 专用 Mesa 驱动", Lib: "libOSMesa_8.so", GLES: 3})
	}
	if gpu == "" || strings.Contains(gpu, "mali") {
		list = append(list, Renderer{ID: "panfrost", Name: "Panfrost", Desc: "Mali 专用 Mesa 驱动", Lib: "libOSMesa_2300d.so", GLES: 3, MaxGame: "1.21.4"})
	}
	return list
}

// zPickRenderer: the configured one if it suits the game, otherwise Krypton Wrapper.
func zPickRenderer(want, game string) Renderer {
	all := zRenderers()
	for _, r := range all {
		if r.ID == want && (r.MaxGame == "" || game == "" || gameCmp(game, r.MaxGame) <= 0) {
			return r
		}
	}
	return all[0]
}

// lwjglFor picks the runtime's LWJGL build for a version: 3.4.1 when the game ships 3.4, else 3.3.3.
func lwjglFor(v *Version) string {
	for _, l := range v.Libraries {
		for _, pre := range []string{"org.lwjgl:lwjgl:", "org.lwjgl.lwjgl:lwjgl:"} {
			if strings.HasPrefix(l.Name, pre) {
				ver := versionParts(strings.TrimPrefix(l.Name, pre))
				if ver[0] > 3 || ver[0] == 3 && ver[1] >= 4 {
					return "3.4.1"
				}
			}
		}
	}
	return "3.3.3"
}

// zSpec is the part of the launch spec the runtime reads (see cn.eririlily.runtime.LaunchSpec).
type zSpec struct {
	GameVersion string   `json:"gameVersion"`
	GameDir     string   `json:"gameDir"`
	LWJGL       string   `json:"lwjgl"`
	LWJGL2      bool     `json:"lwjgl2"`
	ModernArgs  bool     `json:"modernArgs"`
	RendererKey string   `json:"renderer"`
	RAMMB       int      `json:"ramMb"`
	ClientJar   string   `json:"clientJar"`
	ExtraJVM    []string `json:"extraJvm"`
}

// zalithArgs turns the desktop JVM arguments into what the runtime expects: no class path (the
// runtime puts its LWJGL first), no library path, none of the desktop-only flags.
func zalithArgs(args []string) (jvm []string, cp []string) {
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case a == "-cp" || a == "-classpath" || a == "--class-path":
			if i+1 < len(args) {
				cp = rewriteCP(strings.Split(args[i+1], ":"), nil)
				i++
			}
			continue
		case strings.HasPrefix(a, "-Djava.library.path="),
			strings.HasPrefix(a, "-XX:HeapDumpPath="),
			strings.HasPrefix(a, "-Xss"),
			strings.HasPrefix(a, "-Dos.name="),
			strings.HasPrefix(a, "-Dos.version="),
			strings.HasPrefix(a, "-Xmx"), strings.HasPrefix(a, "-Xms"),
			strings.HasPrefix(a, "-XX:+UseG1GC"), strings.HasPrefix(a, "-XX:+UnlockExperimentalVMOptions"),
			strings.HasPrefix(a, "-XX:G1"), strings.HasPrefix(a, "-XX:MaxGCPauseMillis"),
			strings.HasPrefix(a, "-Dminecraft.launcher."):
			continue
		case strings.HasPrefix(a, "-D") && strings.Contains(a, "=") && strings.Contains(a, "/org/lwjgl/"):
			// e.g. Forge's -DlegacyClassPath=…: drop the desktop LWJGL jars there too
			k, v, _ := strings.Cut(a, "=")
			a = k + "=" + strings.Join(rewriteCP(strings.Split(v, ":"), nil), ":")
		}
		jvm = append(jvm, a)
	}
	return jvm, cp
}

// clientJarOf finds the game jar on the class path (versions/<id>/<id>.jar).
func clientJarOf(cp []string, gameDir string) string {
	versions := filepath.Join(gameDir, "versions") + string(filepath.Separator)
	for i := len(cp) - 1; i >= 0; i-- {
		if strings.HasPrefix(cp[i], versions) && strings.HasSuffix(cp[i], ".jar") {
			return cp[i]
		}
	}
	return ""
}

// buildZalithSpec is buildAndroidSpec for the Zalith runtime.
func buildZalithSpec(cfg Config, dataDir string, req LaunchRequest, ga *GameArgs, java *Java) (*AndroidSpec, error) {
	game := GameVersion(cfg.GameDir, req.ID)
	rend := zPickRenderer(cfg.Renderer, game)
	dir := filepath.Join(dataDir, "launch", time.Now().Format("20060102-150405"))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	cleanLaunchDirs(filepath.Join(dataDir, "launch"), 6)

	jvm, cp := zalithArgs(ga.JVM)
	sp := &AndroidSpec{ID: req.ID, Name: req.ID, Game: game, Loader: DetectLoader(ga.Ver), RunDir: ga.RunDir,
		JavaHome: java.Home, JavaMajor: java.Major, MainClass: ga.Main, Renderer: rend, Classpath: cp, JVMArgs: jvm,
		GameArgs: ga.Game, LogFile: filepath.Join(dir, "game.log"), ExitFile: filepath.Join(dir, "exit"),
		PidFile: filepath.Join(dir, "pid"), Settings: settingsOf(cfg), Account: req.Account.Name, Env: map[string]string{},
		GameVersion: game, GameDir: cfg.GameDir, LWJGL: lwjglFor(ga.Ver), LWJGL2: usesLWJGL2(ga.Ver),
		ModernArgs: ga.Ver.Arguments != nil, RendererKey: rend.ID, RAMMB: xmxOf(ga.JVM), ClientJar: clientJarOf(cp, cfg.GameDir)}
	if in, err := Info(cfg.GameDir, req.ID); err == nil && in.EL != nil && in.EL.Name != "" {
		sp.Name = in.EL.Name
	}
	sp.Settings.GuiScale = optionInt(filepath.Join(ga.RunDir, "options.txt"), "guiScale")
	if err := writeJSON(filepath.Join(dir, "spec.json"), sp); err != nil {
		return nil, err
	}
	return sp, nil
}

// xmxOf reads -Xmx<n>m / -Xmx<n>g from JVM arguments (MB), 2048 if absent.
func xmxOf(args []string) int {
	for _, a := range args {
		if v, ok := strings.CutPrefix(a, "-Xmx"); ok {
			low := strings.ToLower(v)
			mul := 1
			switch {
			case strings.HasSuffix(low, "g"):
				mul, low = 1024, strings.TrimSuffix(low, "g")
			case strings.HasSuffix(low, "m"):
				low = strings.TrimSuffix(low, "m")
			}
			if n, err := strconv.Atoi(low); err == nil && n > 0 {
				return n * mul
			}
		}
	}
	return 2048
}
