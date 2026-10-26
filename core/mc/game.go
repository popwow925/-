package mc

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// Hooks receive launch events. Any field may be nil.
type Hooks struct {
	Stage    func(stage string)
	Progress Reporter
	Log      func(line string)
	Ready    func()                                             // game window appeared
	Exit     func(code int, d *Diagnosis, played time.Duration) // d is nil on a clean exit
}

// Game is a running Minecraft process.
type Game struct {
	ID      string
	RunDir  string
	Started time.Time
	cmd     *exec.Cmd
	pid     int  // Android: the app's game process
	killed  bool //
	mu      sync.Mutex
	tail    []string
	done    chan struct{}
}

const tailMax = 3000

func (g *Game) addLine(s string) {
	g.mu.Lock()
	g.tail = append(g.tail, s)
	if len(g.tail) > tailMax {
		g.tail = g.tail[len(g.tail)-tailMax:]
	}
	g.mu.Unlock()
}

// Logs returns a copy of the recent output.
func (g *Game) Logs() []string {
	g.mu.Lock()
	defer g.mu.Unlock()
	return append([]string(nil), g.tail...)
}

func (g *Game) Kill() {
	if g.cmd != nil && g.cmd.Process != nil {
		_ = g.cmd.Process.Kill()
	}
	g.mu.Lock()
	pid := g.pid
	g.killed = true
	g.mu.Unlock()
	killPid(pid)
}

func (g *Game) Done() <-chan struct{} { return g.done }

// LaunchRequest is everything Launch needs.
type LaunchRequest struct {
	Config  Config
	DataDir string
	ID      string
	Account Account
	Server  string // optional server to join
}

func (h Hooks) stage(s string) {
	if h.Stage != nil {
		h.Stage(s)
	}
}

// ResolveJava finds (or downloads) a Java suitable for the instance.
func ResolveJava(ctx context.Context, cfg Config, dataDir, id string, h Hooks) (*Java, error) {
	v, err := Resolve(cfg.GameDir, id)
	if err != nil {
		return nil, err
	}
	major, component := requiredJava(v)
	if v.EL != nil && v.EL.JavaMajor > 0 {
		major, component = v.EL.JavaMajor, ComponentFor(v.EL.JavaMajor)
	}
	if cfg.JavaMode == "manual" && cfg.JavaPath != "" {
		home := strings.TrimSuffix(strings.TrimSuffix(cfg.JavaPath, `\`), "/")
		for _, suf := range []string{`\bin\javaw.exe`, `\bin\java.exe`, "/bin/java"} {
			home = strings.TrimSuffix(home, suf)
		}
		if j := probeJava(home); j != nil {
			return j, nil
		}
		return nil, fmt.Errorf("手动指定的 Java 无效：%s", cfg.JavaPath)
	}
	if j := PickJava(DetectJava(cfg.GameDir, dataDir, false), major); j != nil {
		return j, nil
	}
	if j := PickJava(DetectJava(cfg.GameDir, dataDir, true), major); j != nil {
		return j, nil
	}
	h.stage(fmt.Sprintf("下载 Java %d", major))
	return InstallRuntime(ctx, dataDir, component, cfg.Threads, h.Progress)
}

// RequiredJava reports the Java major version an instance needs.
func RequiredJava(gameDir, id string) int {
	v, err := Resolve(gameDir, id)
	if err != nil {
		return 0
	}
	m, _ := requiredJava(v)
	return m
}

// Launch prepares files and Java in parallel, then starts the game.
func Launch(ctx context.Context, req LaunchRequest, h Hooks) (*Game, error) {
	cfg := req.Config
	Source.Store(cfg.Source)
	v, err := LoadVersion(cfg.GameDir, req.ID)
	if err != nil {
		return nil, fmt.Errorf("找不到实例 %s", req.ID)
	}
	isolated := cfg.Isolation || (v.EL != nil && v.EL.Isolated)

	h.stage("检查文件")
	var java *Java
	var javaErr, fileErr error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		java, javaErr = ResolveJava(ctx, cfg, req.DataDir, req.ID, h)
	}()
	go func() {
		defer wg.Done()
		fileErr = Complete(ctx, cfg.GameDir, req.ID, cfg.Threads, false, h.Progress)
	}()
	wg.Wait()
	if fileErr != nil {
		return nil, fileErr
	}
	if javaErr != nil {
		return nil, javaErr
	}

	h.stage("启动游戏")
	opts := LaunchOptions{
		GameDir: cfg.GameDir, ID: req.ID, Java: java, Account: req.Account,
		MemoryMB: cfg.MemoryMB, ExtraJVM: cfg.JvmArgs, Width: cfg.Width, Height: cfg.Height,
		Fullscreen: cfg.Fullscreen, Isolation: isolated, Server: req.Server,
	}
	if Android {
		return launchAndroid(cfg, req, opts, java, h)
	}
	cmd, runDir, err := BuildCommand(opts)
	if err != nil {
		return nil, err
	}
	InstallOfflineSkin(req.DataDir, runDir, req.Account)
	if cfg.HighPriority {
		setHighPriority(cmd)
	}
	if cfg.DedicatedGPU {
		_ = SetGPUPreference(java.Path, true)
	}
	if cfg.ZhCN {
		chineseByDefault(runDir, GameVersion(cfg.GameDir, req.ID))
	}
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	g := &Game{ID: req.ID, RunDir: runDir, cmd: cmd, done: make(chan struct{})}
	if h.Log != nil {
		h.Log("[EririLily] Java " + java.Version + " · " + java.Path)
		h.Log("[EririLily] 游戏目录 " + runDir)
	}
	g.Started = time.Now()
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("无法启动 Java：%w", err)
	}
	var readyOnce sync.Once
	var rw sync.WaitGroup
	read := func(r io.Reader) {
		defer rw.Done()
		sc := bufio.NewScanner(r)
		sc.Buffer(make([]byte, 64<<10), 4<<20)
		for sc.Scan() {
			line := sc.Text()
			g.addLine(line)
			if h.Log != nil {
				h.Log(line)
			}
			if h.Ready != nil && isReadyLine(line) {
				readyOnce.Do(h.Ready)
			}
		}
	}
	rw.Add(2)
	go read(stdout)
	go read(stderr)
	go func() {
		rw.Wait()
		err := cmd.Wait()
		played := time.Since(g.Started)
		code := 0
		if err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				code = ee.ExitCode()
			} else {
				code = -1
			}
		}
		g.finish(cfg, req, h, code, played)
	}()
	return g, nil
}

func (g *Game) finish(cfg Config, req LaunchRequest, h Hooks, code int, played time.Duration) {
	stampMeta(cfg.GameDir, req.ID, func(m *InstanceMeta) {
		m.LastPlay = g.Started.Unix()
		m.PlayTime += int64(played.Seconds())
	})
	var d *Diagnosis
	if code != 0 && !(g.killed && Android) {
		x := Diagnose(g.RunDir, g.Started, g.Logs(), code)
		d = &x
	}
	if h.Exit != nil {
		h.Exit(code, d, played)
	}
	close(g.done)
}

func launchAndroid(cfg Config, req LaunchRequest, opts LaunchOptions, java *Java, h Hooks) (*Game, error) {
	ga, err := BuildArgs(opts)
	if err != nil {
		return nil, err
	}
	sp, err := buildAndroidSpec(cfg, req.DataDir, req, ga, java)
	if err != nil {
		return nil, err
	}
	InstallOfflineSkin(req.DataDir, ga.RunDir, req.Account)
	if cfg.ZhCN {
		chineseByDefault(ga.RunDir, sp.Game)
	}
	g := &Game{ID: req.ID, RunDir: ga.RunDir, done: make(chan struct{}), Started: time.Now()}
	if h.Log != nil {
		h.Log("[EririLily] Java " + java.Version + " · " + java.Home)
		h.Log("[EririLily] 游戏目录 " + ga.RunDir)
	}
	var once sync.Once
	startAndroid(sp, g, h, func(code int) {
		once.Do(func() { g.finish(cfg, req, h, code, time.Since(g.Started)) })
	})
	return g, nil
}
