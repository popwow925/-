// Package mc is the platform-independent core of EririLily: downloading,
// installing, launching and diagnosing Minecraft: Java Edition.
package mc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

const (
	AppName    = "EririLily"
	AppVersion = "0.7.1"
)

// Config is persisted to <data>/config.json.
type Config struct {
	GameDir       string `json:"gameDir"`    // .minecraft
	Source        string `json:"source"`     // auto | official | bmclapi
	Threads       int    `json:"threads"`    // parallel downloads
	JavaMode      string `json:"javaMode"`   // auto | manual
	JavaPath      string `json:"javaPath"`   // used when JavaMode == manual
	MemoryMB      int    `json:"memoryMB"`   // 0 = auto
	JvmArgs       string `json:"jvmArgs"`    // extra JVM args
	Width         int    `json:"width"`      // 0 = default
	Height        int    `json:"height"`     //
	Fullscreen    bool   `json:"fullscreen"` //
	Isolation     bool   `json:"isolation"`  // per-instance game dir
	Selected      string `json:"selected"`   // selected instance id
	Account       string `json:"account"`    // selected account id
	MSClientID    string `json:"msClientId"` // Azure app id for Microsoft login
	CloseOnLaunch bool   `json:"closeOnLaunch"`
	HighPriority  bool   `json:"highPriority"` // run the game above normal priority
	DedicatedGPU  bool   `json:"dedicatedGpu"` // ask Windows to use the high-performance GPU for Java
	ZhCN          bool   `json:"zhCN"`
	Theme         string `json:"theme"`    // sakura | white | black
	HideMap       bool   `json:"hideMap"`  // turn the world-map preview off
	HideWiki      bool   `json:"hideWiki"` // turn the built-in Minecraft Wiki off

	// Android
	Renderer        string `json:"renderer"`        // auto | gl4es | mobileglues | plugin:<id>
	RenderScale     int    `json:"renderScale"`     // % of the screen resolution the game renders at
	FpsLimit        int    `json:"fpsLimit"`        // 0 = unlimited
	Vsync           bool   `json:"vsync"`           //
	PerfHud         string `json:"perfHud"`         // off | fps | full
	SustainedPerf   bool   `json:"sustainedPerf"`   // ask for sustained performance (less throttling)
	BigCores        bool   `json:"bigCores"`        // pin the render thread to the big cores
	PerfHint        bool   `json:"perfHint"`        // ADPF performance hints
	GyroAim         bool   `json:"gyroAim"`         // aim with the gyroscope
	GyroSens        int    `json:"gyroSens"`        // %
	MouseSpeed      int    `json:"mouseSpeed"`      // touch look speed, %
	ControlsOpacity int    `json:"controlsOpacity"` // %
	ControlsLayout  string `json:"controlsLayout"`  // layout file name
	Haptics         bool   `json:"haptics"`         // vibrate on button press
	RuntimeMirror   string `json:"runtimeMirror"`   // JRE download URL template ({major}, {abi})
	Defaults        int    `json:"defaults"`        // first-run defaults version applied
}

func DefaultConfig(base string) Config {
	c := Config{
		GameDir:   filepath.Join(base, ".minecraft"),
		Source:    "auto",
		Threads:   64,
		JavaMode:  "auto",
		Isolation: true,
	}
	if Android {
		AndroidDefaults(&c)
	}
	return c
}

// AndroidDefaults fills the Android settings for a fresh or older config.
func AndroidDefaults(c *Config) {
	if c.Defaults >= 1 {
		return
	}
	c.Defaults = 1
	c.Renderer, c.RenderScale, c.PerfHud = "auto", 100, "fps"
	c.SustainedPerf, c.BigCores, c.PerfHint, c.Haptics, c.ZhCN = true, true, true, true, true
	c.GyroSens, c.MouseSpeed, c.ControlsOpacity, c.ControlsLayout = 100, 100, 70, "default"
	if c.Threads > 32 {
		c.Threads = 32 // phones: fewer, steadier connections
	}
}

// Store wraps the config file with a mutex.
type Store struct {
	mu   sync.Mutex
	path string
	cfg  Config
}

func OpenStore(dataDir, base string) *Store {
	s := &Store{path: filepath.Join(dataDir, "config.json"), cfg: DefaultConfig(base)}
	if b, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(b, &s.cfg)
	}
	if s.cfg.Threads <= 0 || s.cfg.Threads > 256 {
		s.cfg.Threads = 64
	}
	if s.cfg.Source == "" {
		s.cfg.Source = "auto"
	}
	if s.cfg.GameDir == "" {
		s.cfg.GameDir = filepath.Join(base, ".minecraft")
	}
	if Android {
		AndroidDefaults(&s.cfg)
	}
	return s
}

func (s *Store) Get() Config {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cfg
}

func (s *Store) Update(f func(*Config)) Config {
	s.mu.Lock()
	defer s.mu.Unlock()
	f(&s.cfg)
	_ = writeJSON(s.path, s.cfg)
	return s.cfg
}

func writeJSON(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func readJSON(path string, v any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func jsonUnmarshal(b []byte, v any) error { return json.Unmarshal(b, v) }
